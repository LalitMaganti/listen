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
        super.init(processingEnv);

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
            TypeSpec.Builder classSpec = TypeSpec.classBuilder(className.simpleName() + "Dispatcher")
                    .addSuperinterface(TypeName.get(annotated.asType()));
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
                        .beginControlFlow("for ($T $s : $s)", className, "listener", "mListeners")
                        .addStatement("$s.$s($s)", "listener", method.getSimpleName(), builder.toString())
                        .endControlFlow()
                        .build();

                classSpec.addMethod(MethodSpec.overriding(method)
                        .addCode(code)
                        .build());
            }

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