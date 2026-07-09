package com.kite.di.processor.scan

import com.kite.di.graph.DeferredKind
import com.kite.di.graph.Key
import com.kite.di.graph.Provenance
import com.kite.di.graph.ScopeDef
import com.kite.di.graph.SiteKind
import com.kite.di.processor.ProcessorOptions
import com.kite.di.processor.codegen.RuntimeNames
import com.kite.di.processor.model.BindingDeclKind
import com.kite.di.processor.model.BindingModel
import com.kite.di.processor.model.DependencyModel
import com.kite.di.processor.model.FieldInjectionModel
import com.kite.di.processor.model.Issue
import com.kite.di.processor.model.MemberInjectModel
import com.kite.di.processor.model.ScanResult
import com.kite.di.processor.model.Severity
import com.kite.di.processor.model.TypeRef
import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.FileLocation
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Visibility

private const val ANNOTATIONS = "com.kite.di.annotations"
private const val INJECTABLE = "$ANNOTATIONS.Injectable"
private const val INJECT = "$ANNOTATIONS.Inject"
private const val MODULE = "$ANNOTATIONS.Module"
private const val PROVIDES = "$ANNOTATIONS.Provides"
private const val SCOPE = "$ANNOTATIONS.Scope"
private const val QUALIFIER = "$ANNOTATIONS.Qualifier"
private const val NAMED = "$ANNOTATIONS.Named"

/** KSP symbols → compiler-independent [ScanResult]; structural (V5) checks happen here. */
class BindingScanner(
    private val resolver: Resolver,
    private val options: ProcessorOptions,
) {

    private val issues = mutableListOf<Issue>()
    private val customScopes = mutableMapOf<String, ScopeDef>()

    fun scan(): ScanResult {
        val bindings = mutableListOf<BindingModel>()
        bindings += resolver.getSymbolsWithAnnotation(INJECTABLE)
            .filterIsInstance<KSClassDeclaration>()
            .mapNotNull { scanInjectable(it) }
        bindings += resolver.getSymbolsWithAnnotation(MODULE)
            .filterIsInstance<KSClassDeclaration>()
            .flatMap { scanModule(it) }

        val memberInjects = resolver.getSymbolsWithAnnotation(INJECT)
            .filterIsInstance<KSPropertyDeclaration>()
            .groupBy { it.parentDeclaration }
            .mapNotNull { (parent, props) -> scanMemberInjectTarget(parent, props) }

        val scopes = (ScanResult.BUILT_IN_SCOPES + customScopes.values)
            .distinctBy { it.name }
            .sortedBy { it.level }

        return ScanResult(
            bindings = bindings.sortedBy { it.key.id },
            memberInjects = memberInjects.sortedBy { it.targetType.fqn },
            scopes = scopes,
            issues = issues,
        )
    }

    // --- @Injectable ------------------------------------------------------------

    private fun scanInjectable(cls: KSClassDeclaration): BindingModel? {
        val where = provenance(cls)
        val display = cls.qualifiedName?.asString() ?: cls.simpleName.asString()
        if (cls.classKind != ClassKind.CLASS || cls.isAbstract()) {
            error(
                "@Injectable $display (${where.filePath}:${where.line}) must be a concrete class.\n" +
                    "  hint: for interfaces, annotate the implementation and use @Injectable(bindTo = [${cls.simpleName.asString()}::class])."
            )
            return null
        }

        val constructor = selectConstructor(cls, display, where) ?: return null
        val type = typeRef(cls)
        val qualifier = qualifierOf(cls.annotations, display, where)
        val scope = scopeOf(cls.annotations, display, where)
        val key = Key(type.fqn, qualifier)

        val superTypeFqns = cls.getAllSuperTypes()
            .mapNotNull { it.declaration.qualifiedName?.asString() }.toSet()
        val extraTypes = bindToTypes(cls)
        val extraKeys = mutableListOf<Key>()
        val extraKeyTypes = mutableListOf<TypeRef>()
        for (bound in extraTypes) {
            val boundFqn = bound.declaration.qualifiedName?.asString() ?: continue
            if (boundFqn !in superTypeFqns) {
                error(
                    "@Injectable(bindTo = [...]) on $display (${where.filePath}:${where.line}): " +
                        "$boundFqn is not a supertype of $display.\n" +
                        "  hint: bindTo entries must be interfaces or superclasses the class actually implements."
                )
                continue
            }
            extraKeys += Key(boundFqn, qualifier)
            extraKeyTypes += typeRef(bound.declaration)
        }

        return BindingModel(
            key = key,
            keyType = type,
            extraKeys = extraKeys,
            extraKeyTypes = extraKeyTypes,
            declKind = BindingDeclKind.INJECTABLE,
            scopeLevel = scope?.level,
            scopeName = scope?.name,
            declaration = cls.simpleName.asString(),
            provenance = where,
            dependencies = constructor.parameters.mapNotNull {
                dependency(it, SiteKind.CONSTRUCTOR_PARAM, display)
            },
            suppressions = suppressionsOf(cls),
            targetType = type,
        )
    }

    private fun selectConstructor(
        cls: KSClassDeclaration,
        display: String,
        where: Provenance,
    ): KSFunctionDeclaration? {
        val constructors = cls.getConstructors().toList()
        val injectMarked = constructors.filter { it.hasAnnotation(INJECT) }
        val chosen = when {
            injectMarked.size > 1 -> {
                error(
                    "$display (${where.filePath}:${where.line}) has ${injectMarked.size} @Inject constructors — only one is allowed."
                )
                return null
            }
            injectMarked.size == 1 -> injectMarked.single()
            else -> cls.primaryConstructor ?: constructors.singleOrNull()
        }
        if (chosen == null) {
            error(
                "$display (${where.filePath}:${where.line}) has ${constructors.size} constructors and none marked @Inject.\n" +
                    "  hint: mark the injection constructor with @Inject."
            )
            return null
        }
        if (chosen.getVisibility() == Visibility.PRIVATE) {
            error("$display (${where.filePath}:${where.line}): the injection constructor must not be private.")
            return null
        }
        return chosen
    }

    private fun bindToTypes(cls: KSClassDeclaration): List<KSType> {
        val annotation = cls.annotations.firstOrNull { it.fqn() == INJECTABLE } ?: return emptyList()
        val argument = annotation.arguments.firstOrNull { it.name?.asString() == "bindTo" }?.value
        return (argument as? List<*>)?.filterIsInstance<KSType>() ?: emptyList()
    }

    // --- @Module / @Provides ------------------------------------------------------

    private fun scanModule(module: KSClassDeclaration): List<BindingModel> {
        val where = provenance(module)
        val display = module.qualifiedName?.asString() ?: module.simpleName.asString()
        val isObject = module.classKind == ClassKind.OBJECT
        if (!isObject) {
            val hasNoArgCtor = module.classKind == ClassKind.CLASS && !module.isAbstract() &&
                module.getConstructors().any { it.parameters.isEmpty() && it.getVisibility() != Visibility.PRIVATE }
            if (!hasNoArgCtor) {
                error(
                    "@Module $display (${where.filePath}:${where.line}) must be an `object` or a class with a public no-arg constructor."
                )
                return emptyList()
            }
        }

        return module.getDeclaredFunctions()
            .filter { it.hasAnnotation(PROVIDES) }
            .mapNotNull { scanProvides(module, it, isObject) }
            .toList()
    }

    private fun scanProvides(
        module: KSClassDeclaration,
        function: KSFunctionDeclaration,
        moduleIsObject: Boolean,
    ): BindingModel? {
        val where = provenance(function)
        val display = "${module.simpleName.asString()}.${function.simpleName.asString()}"
        if (function.getVisibility() == Visibility.PRIVATE) {
            error("@Provides $display (${where.filePath}:${where.line}) must not be private.")
            return null
        }
        if (function.extensionReceiver != null) {
            error("@Provides $display (${where.filePath}:${where.line}) must not be an extension function.")
            return null
        }
        val returnType = function.returnType?.resolve()
        val returnDecl = returnType?.declaration
        val returnFqn = returnDecl?.qualifiedName?.asString()
        if (returnType == null || returnFqn == null || returnFqn == "kotlin.Unit") {
            error("@Provides $display (${where.filePath}:${where.line}) must return a concrete type (not Unit).")
            return null
        }
        if (returnType.isMarkedNullable) {
            error(
                "@Provides $display (${where.filePath}:${where.line}) must not return a nullable type — " +
                    "optional dependencies are modeled with default parameter values at the injection site."
            )
            return null
        }

        val keyType = typeRef(returnDecl)
        val qualifier = qualifierOf(function.annotations, display, where)
        val scope = scopeOf(function.annotations, display, where)

        return BindingModel(
            key = Key(returnFqn, qualifier),
            keyType = keyType,
            declKind = BindingDeclKind.PROVIDES,
            scopeLevel = scope?.level,
            scopeName = scope?.name,
            declaration = display,
            provenance = where,
            dependencies = function.parameters.mapNotNull {
                dependency(it, SiteKind.PROVIDES_PARAM, display)
            },
            suppressions = suppressionsOf(function),
            targetType = typeRef(module),
            providesFunction = function.simpleName.asString(),
            moduleIsObject = moduleIsObject,
        )
    }

    // --- @Inject fields -------------------------------------------------------------

    private fun scanMemberInjectTarget(
        parent: KSDeclaration?,
        properties: List<KSPropertyDeclaration>,
    ): MemberInjectModel? {
        if (parent !is KSClassDeclaration) {
            properties.firstOrNull()?.let {
                val where = provenance(it)
                error("@Inject property '${it.simpleName.asString()}' (${where.filePath}:${where.line}) must be declared inside a class.")
            }
            return null
        }
        val where = provenance(parent)
        val display = parent.qualifiedName?.asString() ?: parent.simpleName.asString()

        val fields = properties.mapNotNull { property ->
            val site = provenance(property)
            val name = property.simpleName.asString()
            if (!property.isMutable) {
                error("@Inject field $display.$name (${site.filePath}:${site.line}) must be a `var` (it is assigned by Kite).")
                return@mapNotNull null
            }
            if (property.getVisibility() == Visibility.PRIVATE) {
                error("@Inject field $display.$name (${site.filePath}:${site.line}) must not be private.")
                return@mapNotNull null
            }
            if (scopeOf(property.annotations, display, site, reportScopeCollection = false) != null) {
                error(
                    "@Inject field $display.$name (${site.filePath}:${site.line}) must not carry a scope annotation — " +
                        "scopes belong to bindings, not injection sites."
                )
                return@mapNotNull null
            }
            val resolved = property.type.resolve()
            val (key, typeRef, deferred) = keyOf(resolved, property.annotations, display, site) ?: return@mapNotNull null
            FieldInjectionModel(name, key, typeRef, deferred, site)
        }
        if (fields.isEmpty()) return null
        return MemberInjectModel(typeRef(parent), fields, where)
    }

    // --- shared helpers ----------------------------------------------------------

    private fun dependency(
        parameter: KSValueParameter,
        siteKind: SiteKind,
        ownerDisplay: String,
    ): DependencyModel? {
        val site = provenance(parameter)
        val resolved = parameter.type.resolve()
        val (key, typeRef, deferred) = keyOf(resolved, parameter.annotations, ownerDisplay, site) ?: return null
        return DependencyModel(
            key = key,
            type = typeRef,
            siteKind = siteKind,
            deferred = deferred,
            paramName = parameter.name?.asString(),
            optional = parameter.hasDefault,
            site = site,
        )
    }

    /** Unwraps Provider<T>/Lazy<T>, applies qualifiers → (key, type for codegen, deferred). */
    private fun keyOf(
        type: KSType,
        annotations: Sequence<KSAnnotation>,
        ownerDisplay: String,
        site: Provenance,
    ): Triple<Key, TypeRef, DeferredKind>? {
        var actual = type
        var deferred = DeferredKind.NONE
        when (actual.declaration.qualifiedName?.asString()) {
            RuntimeNames.PROVIDER_FQN -> deferred = DeferredKind.PROVIDER
            RuntimeNames.LAZY_FQN -> deferred = DeferredKind.LAZY
        }
        if (deferred != DeferredKind.NONE) {
            val inner = actual.arguments.firstOrNull()?.type?.resolve()
            if (inner == null) {
                error("$ownerDisplay (${site.filePath}:${site.line}): could not resolve the type argument of ${actual.declaration.simpleName.asString()}<...>.")
                return null
            }
            actual = inner
        }
        val fqn = actual.declaration.qualifiedName?.asString()
        if (fqn == null) {
            error("$ownerDisplay (${site.filePath}:${site.line}): could not resolve a dependency type (is it missing from the classpath?).")
            return null
        }
        val qualifier = qualifierOf(annotations, ownerDisplay, site)
        return Triple(Key(fqn, qualifier), typeRef(actual.declaration), deferred)
    }

    /** First annotation meta-annotated @Qualifier; @Named uses its value, others their FQN. */
    private fun qualifierOf(
        annotations: Sequence<KSAnnotation>,
        ownerDisplay: String,
        where: Provenance,
    ): String? {
        val qualifiers = annotations.filter { annotation ->
            annotation.annotationDeclaration()?.hasAnnotation(QUALIFIER) == true
        }.toList()
        if (qualifiers.size > 1) {
            error("$ownerDisplay (${where.filePath}:${where.line}) has multiple qualifier annotations — at most one is allowed.")
        }
        val annotation = qualifiers.firstOrNull() ?: return null
        return if (annotation.fqn() == NAMED) {
            annotation.arguments.firstOrNull { it.name?.asString() == "value" }?.value as? String
        } else {
            annotation.fqn()
        }
    }

    /** First annotation meta-annotated @Scope(level) → ScopeDef; collects custom scopes. */
    private fun scopeOf(
        annotations: Sequence<KSAnnotation>,
        ownerDisplay: String,
        where: Provenance,
        reportScopeCollection: Boolean = true,
    ): ScopeDef? {
        val scopes = annotations.mapNotNull { annotation ->
            val declaration = annotation.annotationDeclaration() ?: return@mapNotNull null
            val meta = declaration.annotations.firstOrNull { it.fqn() == SCOPE } ?: return@mapNotNull null
            val level = meta.arguments.firstOrNull { it.name?.asString() == "level" }?.value as? Int
                ?: return@mapNotNull null
            ScopeDef(declaration.simpleName.asString(), level)
        }.toList()
        if (scopes.size > 1) {
            error("$ownerDisplay (${where.filePath}:${where.line}) has ${scopes.size} scope annotations — at most one is allowed.")
            return scopes.first()
        }
        val scope = scopes.firstOrNull() ?: return null
        if (reportScopeCollection) customScopes.putIfAbsent(scope.name, scope)
        return scope
    }

    private fun suppressionsOf(symbol: KSAnnotated): Set<String> =
        symbol.annotations.filter { it.shortName.asString() == "Suppress" }
            .flatMap { annotation ->
                annotation.arguments.flatMap { arg ->
                    (arg.value as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                }
            }.toSet()

    private fun typeRef(declaration: KSDeclaration): TypeRef {
        val pkg = declaration.packageName.asString()
        val qualified = declaration.qualifiedName?.asString() ?: declaration.simpleName.asString()
        val simple = qualified.removePrefix(pkg).trimStart('.')
        return TypeRef(pkg, simple.split('.'))
    }

    private fun provenance(node: KSNode): Provenance {
        val location = node.location as? FileLocation
            ?: return Provenance(options.moduleName, "<unknown>", 0)
        val path = location.filePath
            .removePrefix(options.rootDir).trimStart('/', '\\')
        return Provenance(options.moduleName, path, location.lineNumber)
    }

    private fun error(message: String) {
        issues += Issue(Severity.ERROR, message)
    }

    private fun KSAnnotation.fqn(): String? =
        annotationDeclaration()?.qualifiedName?.asString()

    private fun KSAnnotation.annotationDeclaration(): KSClassDeclaration? =
        annotationType.resolve().declaration as? KSClassDeclaration

    private fun KSAnnotated.hasAnnotation(fqn: String): Boolean =
        annotations.any { it.fqn() == fqn }
}
