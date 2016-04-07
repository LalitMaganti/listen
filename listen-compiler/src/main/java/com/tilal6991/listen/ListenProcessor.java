package com.tilal6991.listen;

import com.google.auto.service.AutoService;
import com.squareup.javapoet.*;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.TypeKind;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@AutoService(Processor.class)
public class ListenProcessor extends AbstractProcessor {

    private Filer mFiler;

    private Messager mMessager;

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);

        mFiler = processingEnv.getFiler();
        mMessager = processingEnv.getMessager();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annoations, RoundEnvironment env) {
        for (Element annotatedElement : env.getElementsAnnotatedWith(Listener.class)) {
            if (annotatedElement.getKind() != ElementKind.INTERFACE) {
                mMessager.printMessage(Diagnostic.Kind.ERROR, "Fail");
                return true;
            }

            List<ExecutableElement> methods = new ArrayList<>();
            TypeElement annotated = (TypeElement) annotatedElement;
            for (Element enclosed : annotated.getEnclosedElements()) {
                if (enclosed.getKind() == ElementKind.METHOD) {
                    ExecutableElement method = (ExecutableElement) enclosed;
                    if (method.getReturnType().getKind() != TypeKind.VOID) {
                        mMessager.printMessage(Diagnostic.Kind.ERROR, "Fail");
                        return true;
                    }

                    methods.add(method);
                }
            }

            ClassName className = ClassName.get(annotated);
            TypeName annotatedType = TypeName.get(annotated.asType());
            TypeSpec.Builder classSpec = TypeSpec.classBuilder(className.simpleName() + "Dispatcher")
                    .addSuperinterface(annotatedType);
            for (ExecutableElement method : methods) {
                StringBuilder builder = new StringBuilder();
                List<? extends VariableElement> parameters = method.getParameters();
                for (VariableElement parameter : parameters) {
                    String name = parameter.getSimpleName().toString();
                    if (builder.length() != 0) {
                        builder.append(", ");
                    }
                    builder.append(name);
                }

                CodeBlock code = CodeBlock.builder()
                        .beginControlFlow("for ($T $L = 0; $L < $L.size(); $L++)", TypeName.INT, "i", "i", "mListeners", "i")
                        .addStatement("$L.get($L).$s($L)", "mListeners", "i", method.getSimpleName(), builder.toString())
                        .endControlFlow()
                        .build();

                MethodSpec overridden = MethodSpec.overriding(method)
                        .addCode(code)
                        .build();
                classSpec.addMethod(overridden);
            }

            MethodSpec add = MethodSpec.methodBuilder("addListener")
                    .addParameter(annotatedType, "listener", Modifier.PUBLIC, Modifier.FINAL)
                    .addStatement("$L.add($L)", "mListeners", "listener")
                    .returns(TypeName.VOID)
                    .build();
            classSpec.addMethod(add);

            MethodSpec remove = MethodSpec.methodBuilder("removeListener")
                    .addParameter(annotatedType, "listener", Modifier.PUBLIC, Modifier.FINAL)
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

            try {
                JavaFile javaFile = JavaFile.builder(className.packageName(), classSpec.build()).build();
                javaFile.writeTo(mFiler);
            } catch (IOException e) {
                mMessager.printMessage(Diagnostic.Kind.ERROR, "Fail");
                return true;
            }
        }
        return true;
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Collections.singleton(Listener.class.getCanonicalName());
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }
}