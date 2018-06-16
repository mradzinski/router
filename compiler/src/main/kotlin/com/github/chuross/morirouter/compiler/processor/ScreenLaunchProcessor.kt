package com.github.chuross.morirouter.compiler.processor

import com.github.chuross.morirouter.compiler.PackageNames
import com.github.chuross.morirouter.compiler.Parameters
import com.github.chuross.morirouter.compiler.ProcessorContext
import com.github.chuross.morirouter.compiler.extension.*
import com.github.chuross.morirouter.core.MoriRouterOptions
import com.squareup.javapoet.*
import javax.lang.model.element.Element
import javax.lang.model.element.Modifier
import javax.lang.model.type.DeclaredType
import javax.lang.model.type.TypeMirror

object ScreenLaunchProcessor {

    fun getGeneratedTypeName(element: Element): String {
        return "${element.pathName?.normalize()?.capitalize()}ScreenLauncher"
    }

    fun process(element: Element) {
        if (!element.isRouterPath) return

        validate(element)

        val typeSpec = TypeSpec.classBuilder(getGeneratedTypeName(element)).also { builder ->
            builder.addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            builder.addJavadoc("This class is auto generated.")
            builder.addField(fragmentManagerField())
            builder.addField(optionsField())
            builder.addField(sharedElementsField())
            builder.addFields(paramFields(element))
            builder.addMethod(constructorMethod(element))
            builder.addMethods(optionalParameterMethods(element))
            if (element.manualSharedViewNames?.isNotEmpty() == true) {
                builder.addMethod(manualSharedMappingEnabledMethod(element))
            } else {
                builder.addMethod(sharedElementAddMethod(element))
            }
            builder.addMethod(launchMethod(element))
        }.build()

        val context = ProcessorContext.getInstance()

        JavaFile.builder(context.getPackageName(), typeSpec)
                .build()
                .writeTo(context.filer)
    }

    private fun validate(element: Element) {
        validatePathName(element)
        validateFragmentType(element)
        validateArgumentRequirements(element)
        validateArgumentTypes(element)
    }

    private fun validatePathName(element: Element) {
        if (!element.isRouterPath || element.pathName.isNullOrBlank()) {
            throw IllegalStateException("@RouterPath must have name: ${element.simpleName}")
        }
    }

    private fun validateFragmentType(element: Element) {
        val context = ProcessorContext.getInstance()
        if (!context.typeUtils.isSubtype(element.asType(), context.elementUtils.getTypeElement(PackageNames.SUPPORT_FRAGMENT).asType())) {
            throw IllegalStateException("@RouterPath only support ${PackageNames.SUPPORT_FRAGMENT}: ${element.simpleName}")
        }
    }

    private fun validateArgumentRequirements(element: Element) {
        val argumentElements = element.argumentElements
        val uriArgumentElements = element.uriArgumentElements

        val hasRequiredElement = argumentElements.any { it.isRequiredArgument }
        val hasUriArgumentElement = uriArgumentElements.firstOrNull() != null
        if (hasRequiredElement && hasUriArgumentElement) {
            throw IllegalStateException("can't use both 'required' @Argument and @UriArgument: ${element.simpleName}")
        }
    }

    private fun validateArgumentTypes(element: Element) {
        element.allArgumentElements.forEach {
            when {
                it.asType().kind.isPrimitive -> Unit
                (it.asType() as? DeclaredType)?.typeArguments?.all { isValidType(it) } == true -> Unit
                it.asType() !is DeclaredType && (isValidType(it.asType())) -> Unit
                else -> throw IllegalStateException("not supported type, must be primitive or Serializable or Parcelable: ${element.simpleName}#${it.simpleName}: ${it.asType()}")
            }
        }
    }

    private fun isValidType(typeMirror: TypeMirror): Boolean {
        return when {
            typeMirror.isSerializable() -> true
            typeMirror.isParcelableType() -> true
            else -> false
        }
    }

    private fun fragmentManagerField(): FieldSpec {
        return FieldSpec.builder(ClassName.bestGuess(PackageNames.SUPPORT_FRAGMENT_MANAGER), "fm")
                .addModifiers(Modifier.PRIVATE)
                .build()
    }

    private fun optionsField(): FieldSpec {
        return FieldSpec.builder(MoriRouterOptions::class.java, "options")
                .addModifiers(Modifier.PRIVATE)
                .build()
    }

    private fun sharedElementsField(): FieldSpec {
        val listClassName = ClassName.get("java.util", "List")
        return FieldSpec.builder(ParameterizedTypeName.get(listClassName, ClassName.bestGuess(PackageNames.VIEW)), "sharedElements")
                .addModifiers(Modifier.PRIVATE)
                .initializer("new ${PackageNames.ARRAY_LIST}<>()")
                .build()
    }

    private fun paramFields(element: Element): Iterable<FieldSpec> {
        return element.allArgumentElements.map {
            FieldSpec.builder(TypeName.get(it.asType()), it.paramName.normalize())
                    .addModifiers(Modifier.PRIVATE)
                    .build()
        }
    }

    private fun constructorMethod(element: Element): MethodSpec {
        val requiredRouterParamElements = element.argumentElements.filter { it.isRequiredArgument }

        return MethodSpec.constructorBuilder().also { builder ->
            builder.addParameter(Parameters.nonNull(ClassName.bestGuess(PackageNames.SUPPORT_FRAGMENT_MANAGER), "fm"))
            builder.addParameter(Parameters.nonNull(ClassName.get(MoriRouterOptions::class.java), "options"))
            builder.addStatement("this.fm = fm")
            builder.addStatement("this.options = options")
            requiredRouterParamElements.forEach {
                val name = it.paramName.normalize()
                builder.addParameter(Parameters.nonNull(TypeName.get(it.asType()), name))
                builder.addStatement("this.$name = $name")
            }
        }.build()
    }

    private fun optionalParameterMethods(element: Element): Iterable<MethodSpec> {
        return element.allArgumentElements
                .filter { !it.isRequiredArgument }
                .map {
                    val name = it.paramName.normalize()
                    MethodSpec.methodBuilder(name)
                            .addModifiers(Modifier.PUBLIC)
                            .addParameter(Parameters.nullable(TypeName.get(it.asType()), name))
                            .addStatement("this.$name = $name")
                            .addStatement("return this")
                            .returns(ClassName.bestGuess(getGeneratedTypeName(element)))
                            .build()
                }
    }

    private fun sharedElementAddMethod(element: Element): MethodSpec {
        return MethodSpec.methodBuilder("addSharedElement")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(Parameters.nonNull(ClassName.bestGuess(PackageNames.VIEW), "view"))
                .returns(ClassName.bestGuess(getGeneratedTypeName(element)))
                .addStatement("this.sharedElements.add(view)")
                .addStatement("return this")
                .build()
    }

    private fun manualSharedMappingEnabledMethod(element: Element): MethodSpec {
        return MethodSpec.methodBuilder("manualSharedMapping").also { builder ->
            builder.addModifiers(Modifier.PUBLIC)
            builder.addParameter(Parameters.nonNull(ClassName.bestGuess(PackageNames.CONTEXT), "context"))
            builder.returns(ClassName.bestGuess(getGeneratedTypeName(element)))

            element.manualSharedViewNames?.forEach {
                builder.addStatement("${PackageNames.VIEW} view = new ${PackageNames.VIEW}(context)")
                builder.addStatement("view.setId(0)")
                builder.addStatement("${PackageNames.VIEW_COMPAT}.setTransitionName(view, \"$it\")")
                builder.addStatement("this.sharedElements.add(view)")
            }

            builder.addStatement("return this")
        }.build()
    }

    private fun launchMethod(element: Element): MethodSpec {
        val fragmentClassName = ClassName.get(element.asType())
        val routerParamElements = element.argumentElements
        val routerPathParamElements = element.uriArgumentElements
        val binderTypeName = BindingProcessor.getGeneratedTypeName(element)

        return MethodSpec.methodBuilder("launch").also { builder ->
            builder.addModifiers(Modifier.PUBLIC)

            builder.addStatement("if (fm.isStateSaved()) return")
            builder.addStatement("$fragmentClassName fragment = new $fragmentClassName()")
            builder.addStatement("${PackageNames.BUNDLE} arguments = new ${PackageNames.BUNDLE}()")
            routerParamElements.plus(routerPathParamElements).forEach {
                val name = it.paramName.normalize()
                if (it.asType().isParcelableType()) {
                    builder.addStatement("arguments.putParcelable($binderTypeName.${it.argumentKeyName}, $name)")
                } else {
                    builder.addStatement("arguments.putSerializable($binderTypeName.${it.argumentKeyName}, $name)")
                }
            }
            builder.addStatement("fragment.setArguments(arguments)")

            element.overrideEnterTransitionFactoryName?.also {
                builder.addStatement("Object overrideEnterTransitionSet = new $it().create()")
                builder.addStatement("Object enterTransitionSet = overrideEnterTransitionSet != null ? overrideEnterTransitionSet : options.getEnterTransitionFactory().create()")
                builder.addStatement("if (enterTransitionSet != null) fragment.setEnterTransition(enterTransitionSet)")
            }
                    ?: builder.addStatement("if (options.getEnterTransition() != null) fragment.setEnterTransition(options.getEnterTransition())")

            element.overrideExitTransitionFactoryName?.also {
                builder.addStatement("Object overrideExitTransitionSet = new $it().create()")
                builder.addStatement("Object exitTransitionSet = overrideExitTransitionSet != null ? overrideExitTransitionSet : options.getExitTransitionFactory().create()")
                builder.addStatement("if (exitTransitionSet != null) fragment.setExitTransition(exitTransitionSet)")
            }
                    ?: builder.addStatement("if (options.getExitTransition() != null) fragment.setExitTransition(options.getExitTransition())")

            element.sharedEnterTransitionFactoryName?.also {
                builder.addStatement("Object sharedEnterTransitionSet = new $it().create()")
                builder.addStatement("if (sharedEnterTransitionSet != null) fragment.setSharedElementEnterTransition(sharedEnterTransitionSet)")
            }

            element.sharedExitTransitionFactoryName?.also {
                builder.addStatement("Object sharedExitTransitionSet = new $it().create()")
                builder.addStatement("if (sharedExitTransitionSet != null) fragment.setSharedElementReturnTransition(sharedExitTransitionSet)")
            }

            builder.addStatement("${PackageNames.SUPPORT_FRAGMENT_TRANSACTION} transaction = fm.beginTransaction()")
            builder.beginControlFlow("for (View view : sharedElements)")
            builder.addStatement("if (view.getId() < 0) throw new ${PackageNames.ILLEGAL_STATE_EXCEPTION}(\"view must have id!\")")
            builder.addStatement("String sharedArgumentKey = String.format(\"${BindingProcessor.SHARED_ELEMENT_ARGUMENT_KEY_NAME_FORMAT}\", view.getId())")
            builder.addStatement("String transitionName = ${PackageNames.VIEW_COMPAT}.getTransitionName(view)")
            builder.addStatement("arguments.putString(sharedArgumentKey, transitionName)")
            builder.addStatement("transaction.addSharedElement(view, transitionName)")
            builder.endControlFlow()

            if (element.addNotReplace == true) {
                builder.addStatement("transaction.add(options.getContainerId(), fragment)")
            } else {
                builder.addStatement("transaction.replace(options.getContainerId(), fragment)")
            }

            if (element.addToBackstack == true) {
                builder.addStatement("if (fm.findFragmentById(options.getContainerId()) != null) transaction.addToBackStack(null)")
            }

            builder.addStatement("transaction.commit()")
            builder.addStatement("fm.executePendingTransactions()")
        }.build()

    }
}