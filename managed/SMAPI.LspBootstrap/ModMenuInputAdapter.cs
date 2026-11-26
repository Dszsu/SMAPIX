using System.Reflection;
using HarmonyLib;

namespace SMAPI.LspBootstrap;

/// <summary>
/// Extends Stardew Valley's official menu cursor rendering, hover/click sequencing,
/// and MobileScrollbox touch physics onto third-party SMAPI mod menus without custom UI widgets.
/// </summary>
internal static class ModMenuInputAdapter
{
    private const float DragThresholdUiPx = 12f; // MobileScrollbox.leftClickHeld threshold
    private const float PixelsPerScrollDetentUi = 48f;
    private const float ScrollDampingFactor = 1.05f; // MobileScrollbox.update momentum damping
    private const float MomentumMinSpeedUi = 1f;
    private const int SpeedSampleCount = 8; // MobileScrollbox.speedMeasure length
    private const int WheelDetentDelta = 120;
    private const long LongPressRightClickMs = 650; // Game1.updateActiveMenu rightClickPolling baseline

    private const int TouchStateInvalid = 0;
    private const int TouchStateMoved = 1;
    private const int TouchStatePressed = 2;
    private const int TouchStateReleased = 3;
    private const int ControlTypeGamepad = 3;

    private enum GestureMode
    {
        Idle,
        PendingTap,
        ControlDrag,
        VerticalScroll
    }

    private struct QueuedMouseFrame
    {
        public int X;
        public int Y;
        public bool LeftPressed;
        public bool RightPressed;
    }

    private static Assembly? _svAssembly;
    private static Assembly? _mgAssembly;
    private static Assembly? _smapiAssembly;
    private static bool _initialized;

    // Reflected game / MonoGame members
    private static FieldInfo? _fiCurrentMouseState;
    private static FieldInfo? _fiCurrentTouchState;
    private static PropertyInfo? _piTouchCount;
    private static MethodInfo? _miTouchItem;
    private static PropertyInfo? _piTouchId;
    private static PropertyInfo? _piTouchState;
    private static PropertyInfo? _piTouchPos;
    private static FieldInfo? _fiVecX;
    private static FieldInfo? _fiVecY;

    private static ConstructorInfo? _ciMouseState;
    private static PropertyInfo? _piMsX;
    private static PropertyInfo? _piMsY;
    private static object? _btnReleased;
    private static object? _btnPressed;

    private static PropertyInfo? _piActiveMenu;
    private static MethodInfo? _miGetChildMenu;
    private static PropertyInfo? _piTitleSubMenu;
    private static Type? _tTitleMenu;
    private static Type? _tGameMenu;
    private static MethodInfo? _miGameMenuGetCurrentPage;
    private static PropertyInfo? _piGame1Options;
    private static PropertyInfo? _piUiScale;
    private static FieldInfo? _fiGamepadControls;
    private static FieldInfo? _fiVirtualJoypad;
    private static FieldInfo? _fiMostRecentControlType;
    private static Type? _tControlType;
    private static FieldInfo? _fiSpriteBatch;
    private static MethodInfo? _miDrawMouse;
    private static MethodInfo? _miPerformHoverAction;
    private static PropertyInfo? _piMouseCursorTransparency;

    // Runtime gesture & cursor state
    private static object? _lastLeafMenu;
    private static bool _adapterActive;

    private static GestureMode _mode = GestureMode.Idle;
    private static int _activeTouchId = -1;
    private static int _ignoredTouchId = -1;
    private static int _downRawX;
    private static int _downRawY;
    private static int _curRawX;
    private static int _curRawY;
    private static int _lastRawY;
    private static int _prevEmittedRawX = int.MinValue;
    private static int _prevEmittedRawY = int.MinValue;
    private static long _downTimestampMs;
    private static bool _rightClickTriggeredOnHold;

    private static readonly float[] _speedSamples = new float[SpeedSampleCount];
    private static int _speedSampleIdx;
    private static bool _momentumActive;
    private static float _momentumSpeedUi;
    private static float _scrollAccumUi;
    private static int _syntheticScrollWheel;

    private static readonly Queue<QueuedMouseFrame> _frameQueue = new();

    // drawMouse temporary state & per-frame deduplication
    private static bool _cursorDrawnThisFrame;
    private static bool _drawMouseSavedValid;
    private static bool _drawMouseOldGamepadControls;
    private static object? _drawMouseOldControlType;

    public static void Install(Harmony harmony, Assembly svAsm, Assembly? mgAsm, Assembly? smapiAsm)
    {
        try
        {
            _svAssembly = svAsm;
            _mgAssembly = mgAsm ?? AppDomain.CurrentDomain.GetAssemblies()
                .FirstOrDefault(a => string.Equals(a.GetName().Name, "MonoGame.Framework", StringComparison.OrdinalIgnoreCase));
            _smapiAssembly = smapiAsm;

            if (!ResolveMembers())
            {
                BootstrapLog.Warn("ModMenuInputAdapter skipped: required game/MonoGame members missing.");
                return;
            }

            _initialized = true;
            InstallPatches(harmony);
            BootstrapLog.Info("Installed ModMenuInputAdapter (official-style cursor + touch scroll)");
        }
        catch (Exception ex)
        {
            BootstrapLog.Error($"ModMenuInputAdapter.Install failed: {ex}");
        }
    }

    private static bool ResolveMembers()
    {
        if (_svAssembly == null || _mgAssembly == null) return false;

        var tInputState = _svAssembly.GetType("StardewValley.InputState");
        var tGame1 = _svAssembly.GetType("StardewValley.Game1");
        var tOptions = _svAssembly.GetType("StardewValley.Options");
        var tMenu = _svAssembly.GetType("StardewValley.Menus.IClickableMenu");
        _tTitleMenu = _svAssembly.GetType("StardewValley.Menus.TitleMenu");
        _tGameMenu = _svAssembly.GetType("StardewValley.Menus.GameMenu");
        var tJoypad = _svAssembly.GetType("StardewValley.Mobile.VirtualJoypad");
        _tControlType = _svAssembly.GetType("StardewValley.Mobile.ControlType");

        var tMouseState = _mgAssembly.GetType("Microsoft.Xna.Framework.Input.MouseState");
        var tButtonState = _mgAssembly.GetType("Microsoft.Xna.Framework.Input.ButtonState");
        var tTouchColl = _mgAssembly.GetType("Microsoft.Xna.Framework.Input.Touch.TouchCollection");
        var tTouchLoc = _mgAssembly.GetType("Microsoft.Xna.Framework.Input.Touch.TouchLocation");
        var tVector2 = _mgAssembly.GetType("Microsoft.Xna.Framework.Vector2");

        if (tInputState == null || tGame1 == null || tOptions == null || tMenu == null ||
            tMouseState == null || tButtonState == null || tTouchColl == null || tTouchLoc == null || tVector2 == null)
        {
            return false;
        }

        _fiCurrentMouseState = AccessTools.Field(tInputState, "_currentMouseState");
        _fiCurrentTouchState = AccessTools.Field(tInputState, "_currentTouchState");
        _piTouchCount = AccessTools.Property(tTouchColl, "Count");
        _miTouchItem = AccessTools.Method(tTouchColl, "get_Item", new[] { typeof(int) });
        _piTouchId = AccessTools.Property(tTouchLoc, "Id");
        _piTouchState = AccessTools.Property(tTouchLoc, "State");
        _piTouchPos = AccessTools.Property(tTouchLoc, "Position");
        _fiVecX = AccessTools.Field(tVector2, "X");
        _fiVecY = AccessTools.Field(tVector2, "Y");

        _ciMouseState = tMouseState.GetConstructor(new[]
        {
            typeof(int), typeof(int), typeof(int),
            tButtonState, tButtonState, tButtonState, tButtonState, tButtonState
        });
        _piMsX = AccessTools.Property(tMouseState, "X");
        _piMsY = AccessTools.Property(tMouseState, "Y");
        _btnReleased = Enum.ToObject(tButtonState, 0);
        _btnPressed = Enum.ToObject(tButtonState, 1);

        _piActiveMenu = AccessTools.Property(tGame1, "activeClickableMenu");
        _miGetChildMenu = AccessTools.Method(tMenu, "GetChildMenu");
        _piTitleSubMenu = _tTitleMenu != null ? AccessTools.Property(_tTitleMenu, "subMenu") : null;
        _miGameMenuGetCurrentPage = _tGameMenu != null ? AccessTools.Method(_tGameMenu, "GetCurrentPage") : null;
        _piGame1Options = AccessTools.Property(tGame1, "options");
        _piUiScale = AccessTools.Property(tOptions, "uiScale");
        _fiGamepadControls = AccessTools.Field(tOptions, "gamepadControls");
        _fiVirtualJoypad = AccessTools.Field(tGame1, "virtualJoypad");
        _fiMostRecentControlType = tJoypad != null ? AccessTools.Field(tJoypad, "mostRecentlyUsedControlType") : null;
        _fiSpriteBatch = AccessTools.Field(tGame1, "spriteBatch");
        _miDrawMouse = tMenu.GetMethods(BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance)
            .FirstOrDefault(m => m.Name == "drawMouse" && m.GetParameters().Length == 3);
        _miPerformHoverAction = AccessTools.Method(tMenu, "performHoverAction", new[] { typeof(int), typeof(int) });
        _piMouseCursorTransparency = AccessTools.Property(tGame1, "mouseCursorTransparency");

        return _fiCurrentMouseState != null && _fiCurrentTouchState != null && _piTouchCount != null &&
               _miTouchItem != null && _piTouchId != null && _piTouchState != null && _piTouchPos != null &&
               _fiVecX != null && _fiVecY != null && _ciMouseState != null && _piMsX != null && _piMsY != null &&
               _piActiveMenu != null && _miDrawMouse != null && _fiSpriteBatch != null;
    }

    private static void InstallPatches(Harmony harmony)
    {
        var tInputState = _svAssembly!.GetType("StardewValley.InputState")!;
        var tGame1 = _svAssembly.GetType("StardewValley.Game1")!;

        var mUpdateStates = AccessTools.Method(tInputState, "UpdateStates");
        if (mUpdateStates != null)
        {
            harmony.Patch(
                mUpdateStates,
                postfix: new HarmonyMethod(typeof(ModMenuInputAdapter), nameof(Postfix_UpdateStates)));
        }

        if (_miDrawMouse != null)
        {
            harmony.Patch(
                _miDrawMouse,
                prefix: new HarmonyMethod(typeof(ModMenuInputAdapter), nameof(Prefix_DrawMouse)),
                postfix: new HarmonyMethod(typeof(ModMenuInputAdapter), nameof(Postfix_DrawMouse)),
                finalizer: new HarmonyMethod(typeof(ModMenuInputAdapter), nameof(Finalizer_DrawMouse)));
        }

        var mDrawMenu = AccessTools.Method(tGame1, "DrawMenu");
        if (mDrawMenu != null)
        {
            harmony.Patch(
                mDrawMenu,
                prefix: new HarmonyMethod(typeof(ModMenuInputAdapter), nameof(Prefix_DrawMenu)));
        }

        var tSModHooks = _smapiAssembly?.GetType("StardewModdingAPI.Framework.SModHooks");
        var mTryDrawMenu = tSModHooks != null ? AccessTools.Method(tSModHooks, "TryDrawMenu") : null;
        if (mTryDrawMenu != null)
        {
            harmony.Patch(
                mTryDrawMenu,
                postfix: new HarmonyMethod(typeof(ModMenuInputAdapter), nameof(Postfix_SModHooksTryDrawMenu)));
        }

        var mTitleHover = _tTitleMenu != null ? AccessTools.Method(_tTitleMenu, "performHoverAction", new[] { typeof(int), typeof(int) }) : null;
        if (mTitleHover != null && _miPerformHoverAction != null)
        {
            harmony.Patch(
                mTitleHover,
                postfix: new HarmonyMethod(typeof(ModMenuInputAdapter), nameof(Postfix_TitleMenuPerformHoverAction)));
        }
    }

    private static object? ResolveTopLeafMenu()
    {
        var menu = _piActiveMenu?.GetValue(null);
        if (menu == null) return null;

        menu = WalkChildMenus(menu);
        if (_tTitleMenu != null && _tTitleMenu.IsInstanceOfType(menu))
        {
            var sub = _piTitleSubMenu?.GetValue(null);
            if (sub != null)
            {
                menu = WalkChildMenus(sub);
            }
        }
        if (_tGameMenu != null && _tGameMenu.IsInstanceOfType(menu) && _miGameMenuGetCurrentPage != null)
        {
            var page = _miGameMenuGetCurrentPage.Invoke(menu, null);
            if (page != null)
            {
                menu = WalkChildMenus(page);
            }
        }

        return menu;
    }

    private static object WalkChildMenus(object menu)
    {
        if (_miGetChildMenu == null) return menu;
        for (int i = 0; i < 8; i++)
        {
            var child = _miGetChildMenu.Invoke(menu, null);
            if (child == null) break;
            menu = child;
        }
        return menu;
    }

    private static bool IsModMenu(object? leafMenu)
    {
        if (leafMenu == null || _svAssembly == null) return false;
        return leafMenu.GetType().Assembly != _svAssembly;
    }

    private static float GetUiScale()
    {
        try
        {
            var opts = _piGame1Options?.GetValue(null);
            if (opts != null && _piUiScale != null)
            {
                float scale = (float)_piUiScale.GetValue(opts)!;
                if (scale > 0.1f) return scale;
            }
        }
        catch { }
        return 1f;
    }

    private static bool IsGamepadInControl()
    {
        try
        {
            var opts = _piGame1Options?.GetValue(null);
            if (opts == null || _fiGamepadControls == null || _fiVirtualJoypad == null || _fiMostRecentControlType == null)
                return false;
            bool gamepadEnabled = (bool)_fiGamepadControls.GetValue(opts)!;
            if (!gamepadEnabled) return false;
            var joypad = _fiVirtualJoypad.GetValue(null);
            if (joypad == null) return false;
            int ct = Convert.ToInt32(_fiMostRecentControlType.GetValue(joypad));
            return ct == ControlTypeGamepad;
        }
        catch
        {
            return false;
        }
    }

    private static void ResetGestureState(int ignoreTouchId = -1)
    {
        _mode = GestureMode.Idle;
        _activeTouchId = -1;
        _ignoredTouchId = ignoreTouchId;
        _rightClickTriggeredOnHold = false;
        _momentumActive = false;
        _momentumSpeedUi = 0f;
        _scrollAccumUi = 0f;
        Array.Clear(_speedSamples, 0, _speedSamples.Length);
        _speedSampleIdx = 0;
        _frameQueue.Clear();
    }

    private static void Postfix_UpdateStates(object __instance)
    {
        if (!_initialized) return;

        try
        {
            var leafMenu = ResolveTopLeafMenu();
            if (!IsModMenu(leafMenu))
            {
                if (_adapterActive)
                {
                    _adapterActive = false;
                    _lastLeafMenu = null;
                    _prevEmittedRawX = int.MinValue;
                    _prevEmittedRawY = int.MinValue;
                    ResetGestureState();
                }
                return;
            }

            var touchColl = _fiCurrentTouchState!.GetValue(__instance);
            int touchCount = touchColl != null ? (int)_piTouchCount!.GetValue(touchColl)! : 0;

            if (!ReferenceEquals(leafMenu, _lastLeafMenu))
            {
                int heldId = FindFirstActiveTouchId(touchColl, touchCount);
                _lastLeafMenu = leafMenu;
                _adapterActive = true;
                ResetGestureState(heldId);
                SyncSeedCoordinatesFromCurrentMouse(__instance);
                BootstrapLog.Info($"ModMenuInputAdapter attached to {leafMenu!.GetType().FullName}");
            }

            _adapterActive = true;

            // Yield cleanly to physical controller if active and no touch / queued gesture is running
            if (touchCount == 0 && _mode == GestureMode.Idle && _frameQueue.Count == 0 && !_momentumActive && IsGamepadInControl())
            {
                return;
            }

            var ms = StepModMenuInput(touchColl, touchCount);
            if (ms != null)
            {
                _fiCurrentMouseState!.SetValue(__instance, ms);
            }
        }
        catch (Exception ex)
        {
            BootstrapLog.Error($"Postfix_UpdateStates error: {ex.Message}");
        }
    }

    private static void SyncSeedCoordinatesFromCurrentMouse(object inputState)
    {
        var curMs = _fiCurrentMouseState!.GetValue(inputState);
        if (curMs == null) return;
        _curRawX = (int)_piMsX!.GetValue(curMs)!;
        _curRawY = (int)_piMsY!.GetValue(curMs)!;
        _prevEmittedRawX = _curRawX;
        _prevEmittedRawY = _curRawY;
    }

    private static int FindFirstActiveTouchId(object? touchColl, int count)
    {
        if (touchColl == null || count <= 0) return -1;
        for (int i = 0; i < count; i++)
        {
            var loc = _miTouchItem!.Invoke(touchColl, new object[] { i })!;
            int st = Convert.ToInt32(_piTouchState!.GetValue(loc));
            if (st == TouchStatePressed || st == TouchStateMoved)
            {
                return (int)_piTouchId!.GetValue(loc)!;
            }
        }
        return -1;
    }

    private static bool TryGetTouchById(object? touchColl, int count, int targetId, out int state, out int rawX, out int rawY)
    {
        state = TouchStateInvalid;
        rawX = _curRawX;
        rawY = _curRawY;
        if (touchColl == null || count <= 0 || targetId < 0) return false;

        for (int i = 0; i < count; i++)
        {
            var loc = _miTouchItem!.Invoke(touchColl, new object[] { i })!;
            int id = (int)_piTouchId!.GetValue(loc)!;
            if (id != targetId) continue;

            state = Convert.ToInt32(_piTouchState!.GetValue(loc));
            var pos = _piTouchPos!.GetValue(loc)!;
            rawX = (int)(float)_fiVecX!.GetValue(pos)!;
            rawY = (int)(float)_fiVecY!.GetValue(pos)!;
            return true;
        }
        return false;
    }

    private static bool TryAcquireNewTouch(object? touchColl, int count, out int id, out int state, out int rawX, out int rawY)
    {
        id = -1;
        state = TouchStateInvalid;
        rawX = _curRawX;
        rawY = _curRawY;
        if (touchColl == null || count <= 0) return false;

        for (int i = 0; i < count; i++)
        {
            var loc = _miTouchItem!.Invoke(touchColl, new object[] { i })!;
            int tid = (int)_piTouchId!.GetValue(loc)!;
            int st = Convert.ToInt32(_piTouchState!.GetValue(loc));

            if (tid == _ignoredTouchId)
            {
                if (st == TouchStateReleased || st == TouchStateInvalid)
                {
                    _ignoredTouchId = -1;
                }
                continue;
            }

            if (st == TouchStatePressed || st == TouchStateMoved || st == TouchStateReleased)
            {
                id = tid;
                state = st;
                var pos = _piTouchPos!.GetValue(loc)!;
                rawX = (int)(float)_fiVecX!.GetValue(pos)!;
                rawY = (int)(float)_fiVecY!.GetValue(pos)!;
                return true;
            }
        }
        return false;
    }

    private static object? StepModMenuInput(object? touchColl, int touchCount)
    {
        if (_ignoredTouchId >= 0)
        {
            if (!TryGetTouchById(touchColl, touchCount, _ignoredTouchId, out int igState, out _, out _) ||
                igState == TouchStateReleased || igState == TouchStateInvalid)
            {
                _ignoredTouchId = -1;
            }
        }

        // 1. Drain deferred click frames first (hover-first tap / right-click pulse)
        if (_frameQueue.Count > 0)
        {
            var q = _frameQueue.Dequeue();
            _curRawX = q.X;
            _curRawY = q.Y;
            return BuildMouseState(q.X, q.Y, _syntheticScrollWheel, q.LeftPressed, q.RightPressed);
        }

        float uiScale = GetUiScale();

        // 2. Step active touch gesture or acquire new finger
        if (_mode == GestureMode.Idle)
        {
            if (TryAcquireNewTouch(touchColl, touchCount, out int newId, out int st, out int rx, out int ry))
            {
                // Touch interrupts any leftover momentum immediately (same as MobileScrollbox.receiveLeftClick)
                _momentumActive = false;
                _momentumSpeedUi = 0f;
                _scrollAccumUi = 0f;

                _activeTouchId = newId;
                _downRawX = rx;
                _downRawY = ry;
                _curRawX = rx;
                _curRawY = ry;
                _lastRawY = ry;
                _downTimestampMs = Environment.TickCount64;
                _rightClickTriggeredOnHold = false;
                Array.Clear(_speedSamples, 0, _speedSamples.Length);
                _speedSampleIdx = 0;

                if (st == TouchStateReleased)
                {
                    // Same-frame down+up: guarantee 1 hover frame at target first, then Press -> Release
                    EnqueueTapFrames(rx, ry);
                    _activeTouchId = -1;
                    return BuildMouseState(rx, ry, _syntheticScrollWheel, leftPressed: false, rightPressed: false);
                }

                _mode = GestureMode.PendingTap;
                return BuildMouseState(rx, ry, _syntheticScrollWheel, leftPressed: false, rightPressed: false);
            }
        }
        else
        {
            bool found = TryGetTouchById(touchColl, touchCount, _activeTouchId, out int st, out int rx, out int ry);
            bool released = !found || st == TouchStateReleased || st == TouchStateInvalid;

            if (released)
            {
                if (found)
                {
                    _curRawX = rx;
                    _curRawY = ry;
                }
                return HandleTouchReleased();
            }

            _curRawX = rx;
            _curRawY = ry;
            return HandleTouchHeld(rx, ry, uiScale);
        }

        // 3. No active finger: advance MobileScrollbox momentum coasting if scrolling
        if (_momentumActive)
        {
            if (Math.Abs(_momentumSpeedUi) <= MomentumMinSpeedUi)
            {
                _momentumActive = false;
                _momentumSpeedUi = 0f;
            }
            else
            {
                AccumulateScrollDeltaUi(_momentumSpeedUi);
                _momentumSpeedUi /= ScrollDampingFactor;
            }
        }

        return BuildMouseState(_curRawX, _curRawY, _syntheticScrollWheel, leftPressed: false, rightPressed: false);
    }

    private static object HandleTouchHeld(int rx, int ry, float uiScale)
    {
        float dxUi = (rx - _downRawX) / uiScale;
        float dyUi = (ry - _downRawY) / uiScale;

        switch (_mode)
        {
            case GestureMode.PendingTap:
                if (Math.Abs(dyUi) > DragThresholdUiPx && Math.Abs(dyUi) >= Math.Abs(dxUi))
                {
                    // Switch to official MobileScrollbox vertical scroll mode; suppress LeftButton
                    _mode = GestureMode.VerticalScroll;
                    _scrollAccumUi = 0f;
                    float stepDyUi = (ry - _lastRawY) / uiScale;
                    RecordScrollSample(stepDyUi);
                    AccumulateScrollDeltaUi(stepDyUi);
                    _lastRawY = ry;
                    return BuildMouseState(rx, ry, _syntheticScrollWheel, leftPressed: false, rightPressed: false);
                }

                if (Math.Abs(dxUi) > DragThresholdUiPx && Math.Abs(dxUi) > Math.Abs(dyUi))
                {
                    // Horizontal gesture -> widget slider / drag bar
                    // Press at initial down point so hit-test lands on the touched track/thumb first
                    _mode = GestureMode.ControlDrag;
                    return BuildMouseState(_downRawX, _downRawY, _syntheticScrollWheel, leftPressed: true, rightPressed: false);
                }

                // Still inside 12px tap deadzone: check long-press right click
                if (!_rightClickTriggeredOnHold &&
                    (Environment.TickCount64 - _downTimestampMs) >= LongPressRightClickMs)
                {
                    _rightClickTriggeredOnHold = true;
                    return BuildMouseState(rx, ry, _syntheticScrollWheel, leftPressed: false, rightPressed: true);
                }

                _lastRawY = ry;
                return BuildMouseState(rx, ry, _syntheticScrollWheel, leftPressed: false, rightPressed: false);

            case GestureMode.ControlDrag:
                _lastRawY = ry;
                return BuildMouseState(rx, ry, _syntheticScrollWheel, leftPressed: true, rightPressed: false);

            case GestureMode.VerticalScroll:
            {
                float stepDyUi = (ry - _lastRawY) / uiScale;
                _lastRawY = ry;
                RecordScrollSample(stepDyUi);
                AccumulateScrollDeltaUi(stepDyUi);
                return BuildMouseState(rx, ry, _syntheticScrollWheel, leftPressed: false, rightPressed: false);
            }

            default:
                return BuildMouseState(rx, ry, _syntheticScrollWheel, leftPressed: false, rightPressed: false);
        }
    }

    private static object HandleTouchReleased()
    {
        var finishedMode = _mode;
        bool alreadyRightClicked = _rightClickTriggeredOnHold;
        _mode = GestureMode.Idle;
        _activeTouchId = -1;
        _rightClickTriggeredOnHold = false;

        switch (finishedMode)
        {
            case GestureMode.PendingTap:
                if (alreadyRightClicked)
                {
                    return BuildMouseState(_curRawX, _curRawY, _syntheticScrollWheel, leftPressed: false, rightPressed: false);
                }

                // Guarantee oldMouseState matches the exact tap point before LeftButton=Pressed transitions
                if (_prevEmittedRawX == _curRawX && _prevEmittedRawY == _curRawY)
                {
                    _frameQueue.Enqueue(new QueuedMouseFrame
                    {
                        X = _curRawX,
                        Y = _curRawY,
                        LeftPressed = false,
                        RightPressed = false
                    });
                    return BuildMouseState(_curRawX, _curRawY, _syntheticScrollWheel, leftPressed: true, rightPressed: false);
                }

                EnqueueTapFrames(_curRawX, _curRawY);
                return BuildMouseState(_curRawX, _curRawY, _syntheticScrollWheel, leftPressed: false, rightPressed: false);

            case GestureMode.ControlDrag:
                return BuildMouseState(_curRawX, _curRawY, _syntheticScrollWheel, leftPressed: false, rightPressed: false);

            case GestureMode.VerticalScroll:
                // Exact MobileScrollbox.releaseLeftClick momentum average
                float sum = 0f;
                for (int i = 0; i < SpeedSampleCount; i++)
                {
                    sum += _speedSamples[i];
                }
                _momentumSpeedUi = sum / SpeedSampleCount;
                _momentumActive = Math.Abs(_momentumSpeedUi) > MomentumMinSpeedUi;
                return BuildMouseState(_curRawX, _curRawY, _syntheticScrollWheel, leftPressed: false, rightPressed: false);

            default:
                return BuildMouseState(_curRawX, _curRawY, _syntheticScrollWheel, leftPressed: false, rightPressed: false);
        }
    }

    private static void EnqueueTapFrames(int rx, int ry)
    {
        _frameQueue.Enqueue(new QueuedMouseFrame { X = rx, Y = ry, LeftPressed = true, RightPressed = false });
        _frameQueue.Enqueue(new QueuedMouseFrame { X = rx, Y = ry, LeftPressed = false, RightPressed = false });
    }

    private static void RecordScrollSample(float stepDyUi)
    {
        _speedSamples[_speedSampleIdx] = stepDyUi;
        _speedSampleIdx = (_speedSampleIdx + 1) % SpeedSampleCount;
    }

    private static void AccumulateScrollDeltaUi(float deltaYUi)
    {
        // Finger moving down (deltaYUi > 0) -> positive WheelDelta (+120) -> scroll up (ShopMenu / GMCM convention)
        // Finger moving up (deltaYUi < 0) -> negative WheelDelta (-120) -> scroll down
        _scrollAccumUi += deltaYUi;
        while (Math.Abs(_scrollAccumUi) >= PixelsPerScrollDetentUi)
        {
            int dir = _scrollAccumUi > 0f ? 1 : -1;
            _scrollAccumUi -= dir * PixelsPerScrollDetentUi;
            _syntheticScrollWheel += dir * WheelDetentDelta;
        }
    }

    private static object BuildMouseState(int x, int y, int scrollWheel, bool leftPressed, bool rightPressed)
    {
        _prevEmittedRawX = x;
        _prevEmittedRawY = y;
        var left = leftPressed ? _btnPressed! : _btnReleased!;
        var right = rightPressed ? _btnPressed! : _btnReleased!;
        return _ciMouseState!.Invoke(new object[]
        {
            x, y, scrollWheel, left, _btnReleased!, right, _btnReleased!, _btnReleased!
        });
    }

    private static void Prefix_DrawMenu()
    {
        _cursorDrawnThisFrame = false;
    }

    private static bool Prefix_DrawMouse(object __instance, ref bool ignore_transparency, ref int cursor)
    {
        if (!_initialized || !_adapterActive) return true;

        var leaf = ResolveTopLeafMenu();
        if (leaf == null) return true;

        // Only allow the topmost leaf menu to draw the cursor; suppress lower layers
        // (e.g. GameMenu / ModConfigMenu beneath SpecificModConfigMenu) so their cursor
        // neither renders underneath the foreground dialog nor consumes _cursorDrawnThisFrame.
        if (!ReferenceEquals(__instance, leaf))
        {
            return false;
        }

        if (_cursorDrawnThisFrame)
        {
            return false;
        }

        _cursorDrawnThisFrame = true;

        try
        {
            var opts = _piGame1Options?.GetValue(null);
            var joypad = _fiVirtualJoypad?.GetValue(null);
            if (opts != null && _fiGamepadControls != null && joypad != null && _fiMostRecentControlType != null && _tControlType != null)
            {
                _drawMouseOldGamepadControls = (bool)_fiGamepadControls.GetValue(opts)!;
                _drawMouseOldControlType = _fiMostRecentControlType.GetValue(joypad);
                _drawMouseSavedValid = true;

                if (cursor < 0 && Convert.ToInt32(_drawMouseOldControlType) != ControlTypeGamepad)
                {
                    cursor = 0;
                }

                if (!ignore_transparency && _piMouseCursorTransparency != null)
                {
                    float alpha = (float)_piMouseCursorTransparency.GetValue(null)!;
                    if (alpha < 0.99f)
                    {
                        ignore_transparency = true;
                    }
                }

                // Temporarily unlock official IClickableMenu.drawMouse mobile gate
                _fiGamepadControls.SetValue(opts, true);
                _fiMostRecentControlType.SetValue(joypad, Enum.ToObject(_tControlType, ControlTypeGamepad));
            }
        }
        catch { }

        return true;
    }

    private static void Postfix_DrawMouse() => RestoreDrawMouseState();

    private static Exception? Finalizer_DrawMouse(Exception? __exception)
    {
        RestoreDrawMouseState();
        return __exception;
    }

    private static void RestoreDrawMouseState()
    {
        if (!_drawMouseSavedValid) return;
        _drawMouseSavedValid = false;
        try
        {
            var opts = _piGame1Options?.GetValue(null);
            var joypad = _fiVirtualJoypad?.GetValue(null);
            if (opts != null && _fiGamepadControls != null)
                _fiGamepadControls.SetValue(opts, _drawMouseOldGamepadControls);
            if (joypad != null && _fiMostRecentControlType != null && _drawMouseOldControlType != null)
                _fiMostRecentControlType.SetValue(joypad, _drawMouseOldControlType);
        }
        catch { }
    }

    // Fallback cursor pass while SpriteBatch + UIMode are still active inside Game1.DrawMenu
    private static void Postfix_SModHooksTryDrawMenu(object menu)
    {
        if (!_initialized || !_adapterActive || _cursorDrawnThisFrame || menu == null) return;

        try
        {
            var leaf = ResolveTopLeafMenu();
            if (!IsModMenu(leaf)) return;
            // Only draw once on the topmost leaf or root TitleMenu whose subMenu chain matches leaf
            var sub = _piTitleSubMenu?.GetValue(null);
            bool isTarget = ReferenceEquals(menu, leaf) ||
                            (_tTitleMenu != null && _tTitleMenu.IsInstanceOfType(menu) &&
                             sub != null && ReferenceEquals(WalkChildMenus(sub), leaf));
            if (!isTarget) return;

            var sb = _fiSpriteBatch?.GetValue(null);
            if (sb != null)
            {
                _miDrawMouse?.Invoke(leaf, new object[] { sb, true, 0 });
            }
        }
        catch { }
    }

    // Vanilla TitleMenu.performHoverAction does not forward to TitleMenu.subMenu; fix for mod submenus (e.g. GMCM on title screen)
    private static void Postfix_TitleMenuPerformHoverAction(int x, int y)
    {
        if (!_initialized) return;
        try
        {
            var sub = _piTitleSubMenu?.GetValue(null);
            if (sub != null && IsModMenu(WalkChildMenus(sub)))
            {
                _miPerformHoverAction?.Invoke(sub, new object[] { x, y });
            }
        }
        catch { }
    }
}
