package com.tilal6991.listen;

import com.google.auto.service.AutoService;
import com.google.common.collect.ImmutableSet;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

@AutoService(Processor.class)
public class ListenProcessor extends AbstractProcessor {

    private Filer mFiler;

    private Messager mMessager;

    private Types mTypes;

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);

        mFiler = processingEnv.getFiler();
        mMessager = processingEnv.getMessager();
        mTypes = processingEnv.getTypeUtils();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annoations, RoundEnvironment env) {
        for (Element annotatedElement : env.getElementsAnnotatedWith(Listener.class)) {
            if (annotatedElement.getKind() != ElementKind.INTERFACE) {
                mMessager.printMessage(Diagnostic.Kind.ERROR, "Fail");
                return true;
            }

            TypeElement annotated = (TypeElement) annotatedElement;

            Set<ExecutableElement> methods = new HashSet<ExecutableElement>();
            if (!getAllMethodsEverywhere(annotated, methods)) {
                return true;
            }

            boolean success = generateListenerDispatcher(annotated, methods);
            if (!success) {
                return true;
            }
        }

        for (Element annotatedElement : env.getElementsAnnotatedWith(EventObjectListener.class)) {
            if (annotatedElement.getKind() != ElementKind.INTERFACE) {
                mMessager.printMessage(Diagnostic.Kind.ERROR, "Fail");
                return true;
            }

            TypeElement annotated = (TypeElement) annotatedElement;

            Set<ExecutableElement> methods = new HashSet<ExecutableElement>();
            if (!getAllMethodsEverywhere(annotated, methods)) {
                return true;
            }

            boolean success = generateListenerEventDispatcher(annotated, methods);
            if (!success) {
                return true;
            }
        }
        return true;
    }

    private boolean generateListenerEventDispatcher(TypeElement annotated, Set<ExecutableElement> methods) {
        EventObjectListener mainAnnotation = annotated.getAnnotation(EventObjectListener.class);

        ClassName className = ClassName.get(annotated);
        TypeName annotatedType = TypeName.get(annotated.asType());
        String eventsName = mainAnnotation.eventsClassName();

        TypeSpec.Builder eventsClass = TypeSpec.classBuilder(eventsName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL);

        TypeSpec.Builder classSpec = TypeSpec.classBuilder(mainAnnotation.className())
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addSuperinterface(annotatedType);

        for (ExecutableElement method : methods) {
            String oldName = method.getSimpleName().toString();
            String name = Character.toUpperCase(oldName.charAt(0)) + oldName.substring(1);

            TypeSpec.Builder event = TypeSpec.classBuilder(name)
                    .addModifiers(Modifier.STATIC, Modifier.PUBLIC)
                    .addSuperinterface(ClassName.get(className.packageName(), eventsName, "Event"));
            MethodSpec.Builder constructor = MethodSpec.constructorBuilder();
            CodeBlock.Builder constructorBlock = CodeBlock.builder();
            for (VariableElement parameter : method.getParameters()) {
                ParamName annotation = parameter.getAnnotation(ParamName.class);
                String param = annotation == null ? parameter.getSimpleName().toString() : annotation.value();

                TypeName type = TypeName.get(parameter.asType());
                event.addField(type, param, Modifier.FINAL, Modifier.PUBLIC);

                constructor.addParameter(ParameterSpec.builder(type, param).build());
                constructorBlock.addStatement("this.$L = $L", param, param);
            }

            constructor.addCode(constructorBlock.build());
            event.addMethod(constructor.build());
            eventsClass.addType(event.build());

            // Add the event specific method.
            classSpec.addMethod(MethodSpec.overriding(method)
                    .addCode(CodeBlock.builder()
                            .addStatement("$L(new $N.$N($L))", "onEvent",
                                    eventsName, name, getParameterList(method))
                            .build())
                    .build());
        }

        // Private constructor for event class.
        eventsClass.addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());

        // Root event class.
        eventsClass.addType(TypeSpec.interfaceBuilder("Event")
                .addModifiers(Modifier.STATIC, Modifier.PUBLIC)
                .build());

        // Root event method.
        classSpec.addMethod(MethodSpec.methodBuilder("onEvent")
                .addParameter(ClassName.get(className.packageName(), eventsName, "Event"), "event")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT).build());

        // Write out both classes to disk.
        try {
            JavaFile javaFile = JavaFile.builder(className.packageName(), eventsClass.build()).build();
            javaFile.writeTo(mFiler);

            javaFile = JavaFile.builder(className.packageName(), classSpec.build()).build();
            javaFile.writeTo(mFiler);
        } catch (IOException e) {
            mMessager.printMessage(Diagnostic.Kind.ERROR, "Fail");
            return false;
        }
        return true;
    }

    private boolean generateListenerDispatcher(TypeElement annotated, Set<ExecutableElement> methods) {
        ClassName className = ClassName.get(annotated);
        TypeName annotatedType = TypeName.get(annotated.asType());
        TypeSpec.Builder classSpec = TypeSpec.classBuilder(className.simpleName() + "Dispatcher")
                .addSuperinterface(annotatedType);
        for (ExecutableElement method : methods) {
            String parameterList = getParameterList(method);

            CodeBlock code = CodeBlock.builder()
                    .beginControlFlow("for ($T $L = 0; $L < $L.size(); $L++)", TypeName.INT, "i", "i", "mListeners", "i")
                    .addStatement("$L.get($L).$L($L)", "mListeners", "i", method.getSimpleName(), parameterList)
                    .endControlFlow()
                    .build();

            MethodSpec overridden = MethodSpec.overriding(method)
                    .addCode(code)
                    .build();
            classSpec.addMethod(overridden);
        }

        generateListenersField(annotatedType, classSpec);

        try {
            JavaFile javaFile = JavaFile.builder(className.packageName(), classSpec.build()).build();
            javaFile.writeTo(mFiler);
        } catch (IOException e) {
            mMessager.printMessage(Diagnostic.Kind.ERROR, "Fail");
            return false;
        }
        return true;
    }

    private void generateListenersField(TypeName annotatedType, TypeSpec.Builder classSpec) {
        MethodSpec add = MethodSpec.methodBuilder("addListener")
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addParameter(annotatedType, "listener")
                .addStatement("$L.add($L)", "mListeners", "listener")
                .returns(TypeName.VOID)
                .build();
        classSpec.addMethod(add);

        MethodSpec remove = MethodSpec.methodBuilder("removeListener")
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addParameter(annotatedType, "listener")
                .addStatement("$L.remove($L)", "mListeners", "listener")
                .returns(TypeName.VOID)
                .build();
        classSpec.addMethod(remove);

        FieldSpec listeners = FieldSpec
                .builder(ParameterizedTypeName.get(
                        ClassName.get(List.class), annotatedType), "mListeners", Modifier.PRIVATE, Modifier.FINAL)
                .initializer("new $T<>()", ArrayList.class)
                .build();
        classSpec.addField(listeners);
    }

    private boolean getAllMethodsEverywhere(TypeElement annotated, Set<ExecutableElement> methods) {
        for (Element enclosed : annotated.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.METHOD) {
                ExecutableElement method = (ExecutableElement) enclosed;
                if (method.getReturnType().getKind() != TypeKind.VOID) {
                    mMessager.printMessage(Diagnostic.Kind.ERROR, "Fail");
                    return false;
                }

                methods.add(method);
            }
        }

        for (TypeMirror mirror : annotated.getInterfaces()) {
            TypeElement element = (TypeElement) mTypes.asElement(mirror);
            if (!getAllMethodsEverywhere(element, methods)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return ImmutableSet.of(Listener.class.getCanonicalName(),
                EventObjectListener.class.getCanonicalName());
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    public String getParameterList(ExecutableElement method) {
        StringBuilder builder = new StringBuilder();
        List<? extends VariableElement> parameters = method.getParameters();
        for (VariableElement parameter : parameters) {
            String name = parameter.getSimpleName().toString();
            if (builder.length() != 0) {
                builder.append(", ");
            }
            builder.append(name);
        }
        return builder.toString();
    }
}