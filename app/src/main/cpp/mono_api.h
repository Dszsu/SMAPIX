#pragma once

#include <cstdint>

using MonoDomain = void;
using MonoThread = void;
using MonoAssembly = void;
using MonoImage = void;
using MonoClass = void;
using MonoMethod = void;
using MonoString = void;
using MonoObject = void;

struct MonoApi {
    MonoDomain *(*mono_get_root_domain)(void) = nullptr;
    MonoDomain *(*mono_domain_get)(void) = nullptr;
    MonoThread *(*mono_thread_current)(void) = nullptr;
    MonoDomain *(*mono_jit_thread_attach)(MonoDomain *domain) = nullptr;
    MonoAssembly *(*mono_domain_assembly_open)(MonoDomain *domain, const char *name) = nullptr;
    MonoAssembly *(*mono_assembly_open)(const char *filename, int *status) = nullptr;
    MonoImage *(*mono_assembly_get_image)(MonoAssembly *assembly) = nullptr;
    MonoClass *(*mono_class_from_name)(MonoImage *image, const char *name_space, const char *name) = nullptr;
    MonoMethod *(*mono_class_get_method_from_name)(MonoClass *klass, const char *name, int param_count) = nullptr;
    MonoString *(*mono_string_new)(MonoDomain *domain, const char *text) = nullptr;
    MonoObject *(*mono_runtime_invoke)(MonoMethod *method, void *obj, void **params, MonoObject **exc) = nullptr;
    MonoString *(*mono_object_to_string)(MonoObject *obj, MonoObject **exc) = nullptr;
    char *(*mono_string_to_utf8)(MonoString *string_obj) = nullptr;
};
