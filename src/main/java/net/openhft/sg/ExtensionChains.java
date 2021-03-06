/*
 * Copyright (C) 2015,2016  higherfrequencytrading.com
 * Copyright (C) 2016 Roman Leventov
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.openhft.sg;

import spoon.reflect.code.*;
import spoon.reflect.declaration.*;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtActualTypeContainer;
import spoon.reflect.reference.CtParameterReference;
import spoon.reflect.reference.CtTypeParameterReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.CtScanner;
import spoon.reflect.visitor.Filter;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static java.util.Collections.reverse;
import static net.openhft.sg.StageGraphCompilationException.sgce;
import static spoon.reflect.declaration.ModifierKind.*;

public final class ExtensionChains {
    
    private ExtensionChains() {}
    
    public static void mergeStagedChain(List<CtClass<?>> chain) {
        //noinspection unchecked
        mergeStagedChainInner((List) chain);
    }

    private static <T> void mergeStagedChainInner(List<CtClass<T>> chain) {
        if (chain.size() == 1)
            return;
        reverse(chain);
        CtClass<T> toMerge = chain.get(0);
        for (int i = 1; i < chain.size(); i++) {
            CtClass<T> mergeInto = chain.get(i);
            replaceInstantiatedTypeParams(toMerge, mergeInto);
            toMerge.getAnnotations().stream()
                    .forEach((CtAnnotation<? extends Annotation> a) -> {
                        if (mergeInto.getAnnotation(a.getActualAnnotation().getClass()) == null)
                            add(mergeInto, a, mergeInto::addAnnotation);
                    });
            toMerge.getSuperInterfaces().forEach(mergeInto::addSuperInterface);
            toMerge.getAnonymousExecutables()
                    .forEach(b -> add(mergeInto, b, mergeInto::addAnonymousExecutable));
            toMerge.getNestedTypes().forEach(nt -> add(mergeInto, nt, mergeInto::addNestedType));
            toMerge.getFields().forEach(f -> add(mergeInto, f, mergeInto::addField));
            for (CtMethod<?> methodToMerge : toMerge.getMethods()) {
                processMethod(mergeInto, toMerge, methodToMerge);
            }
            final CtClass<T> finalToMerge = toMerge;
            mergeInto.getConstructors().forEach(c -> processConstructor(c, finalToMerge));
            mergeInto.setSuperclass(toMerge.getSuperclass());
            toMerge = mergeInto;
        }
    }

    private static <T> void replaceInstantiatedTypeParams(CtClass<T> toMerge, CtClass<T> mergeInto) {
        List<CtTypeReference<?>> typeArgs = mergeInto.getSuperclass().getActualTypeArguments();
        typeArgs.stream().filter(ta -> !(ta instanceof CtTypeParameterReference))
            .forEach(ta -> {
                int instantiatedParamIndex = typeArgs.indexOf(ta);
                CtTypeReference<?> instantiatedTypeParam =
                        toMerge.getFormalTypeParameters().get(instantiatedParamIndex);
                
                toMerge.accept(new CtScanner() {

                    @Override
                    public void scan(CtElement element) {
                        if (element instanceof CtActualTypeContainer) {
                            CtActualTypeContainer ref = (CtActualTypeContainer) element;
                            ArrayList<CtTypeReference<?>> typeArgs =
                                    new ArrayList<>(ref.getActualTypeArguments());
                            replaceInList(element.getFactory(), typeArgs);
                            ref.setActualTypeArguments(typeArgs);
                        }
                        if (element instanceof CtTypeParameterReference) {
                            CtTypeParameterReference ref = (CtTypeParameterReference) element;
                            ArrayList<CtTypeReference<?>> bounds = new ArrayList<>(ref.getBounds());
                            replaceInList(element.getFactory(), bounds);
                            ref.setBounds(bounds);
                        }
                        if (element instanceof CtTypedElement) {
                            CtTypedElement typed = (CtTypedElement) element;
                            CtTypeReference type = typed.getType();
                            if (type != null && instantiatedTypeParam.getSimpleName().equals(
                                    type.getSimpleName())) {
                                typed.setType(element.getFactory().Core().clone(ta));
                            }
                        }
                        if (element instanceof CtExpression) {
                            ArrayList<CtTypeReference<?>> typeCasts =
                                    new ArrayList<>(((CtExpression) element).getTypeCasts());
                            replaceInList(element.getFactory(), typeCasts);
                            ((CtExpression) element).setTypeCasts(typeCasts);
                        }
                        super.scan(element);
                    }

                    private void replaceInList(Factory f, List<CtTypeReference<?>> types) {
                        for (int i = 0; i < types.size(); i++) {
                            CtTypeReference<?> arg = types.get(i);
                            if (instantiatedTypeParam.getSimpleName().equals(arg.getSimpleName())) {
                                types.set(i, f.Core().clone(ta));
                            }
                        }
                    }

                });
            });
    }

    private static <T> void processConstructor(CtConstructor<T> c, CtClass<T> toMerge) {
        CtStatement firstStmt = c.getBody().getStatements().get(0);
        if (firstStmt instanceof CtInvocation) {
            CtInvocation<?> superConstructorCall = (CtInvocation) firstStmt;
            if (!(superConstructorCall.getExecutable().getDeclaration() instanceof CtConstructor))
                return;
            CtConstructor superConstructor = (CtConstructor) superConstructorCall
                    .getExecutable().getDeclaration();
            if (superConstructor.getDeclaringType() == toMerge) {
                CtBlock superConstructorBody =
                        c.getFactory().Core().clone(superConstructor.getBody());
                superConstructorBody.accept(new CtScanner() {
                    @Override
                    public <T> void visitCtParameterReference(
                            CtParameterReference<T> ref) {
                        int parameterOrder = superConstructor.getParameters()
                                .indexOf(ref.getDeclaration());
                        ref.setDeclaringExecutable(c.getReference());
                        CtExpression<?> arg =
                                superConstructorCall.getArguments().get(parameterOrder);
                        if (!(arg instanceof CtVariableAccess))
                            throw sgce("super() should be directly called in " + c);
                        CtVariable param = ((CtVariableAccess) arg).getVariable().getDeclaration();
                        if (!(param instanceof CtParameter))
                            throw sgce("super() should be directly called in " + c);
                        ref.setSimpleName(param.getSimpleName());

                        super.visitCtParameterReference(ref);
                    }
                });
                c.getBody().removeStatement(firstStmt);
                List<CtStatement> superConstructorBodyStatements =
                        superConstructorBody.getStatements();
                for (int i = superConstructorBodyStatements.size() - 1; i >= 0; i--) {
                    c.getBody().insertBegin(superConstructorBodyStatements.get(i));

                }
            }
        }
    }

    private static  <T> void processMethod(CtClass<?> mergeInto, CtClass<?> toMerge,
                                           CtMethod<T> methodToMerge) {
        if (methodToMerge.hasModifier(STATIC)) {
            add(mergeInto, methodToMerge, mergeInto::addMethod);
            return;
        }
        
        Optional<CtMethod<?>> overridingMethod = mergeInto.getMethods().stream()
                .filter(m -> MethodNode.overrides(m, methodToMerge)).findFirst();
        if (overridingMethod.isPresent()) {
            @SuppressWarnings("unchecked")
            CtMethod<T> overriding = (CtMethod<T>) overridingMethod.get();
            boolean shouldRemoveAnn = methodToMerge.getAnnotation(Override.class) == null;
            if (!methodToMerge.hasModifier(ABSTRACT) && !overriding.hasModifier(ABSTRACT))
                processOverridden(mergeInto, toMerge, methodToMerge);
            if (shouldRemoveAnn)
                removeAnnotation(overriding, Override.class);
        } else {
            if (!methodToMerge.hasModifier(ABSTRACT))
                add(mergeInto, methodToMerge, mergeInto::addMethod);
        }
    }

    private static <T> void processOverridden(CtClass<?> mergeInto, CtClass<?> toMerge,
                                              final CtMethod<T> methodToMerge) {
        List<CtInvocation<T>> superInvocations = mergeInto.getElements(
                new Filter<CtInvocation<T>>() {
                    @Override
                    public boolean matches(CtInvocation<T> invocation) {
                        if (!(invocation.getTarget() instanceof CtSuperAccess))
                            return false;
                        CtExecutable<?> m = invocation.getExecutable().getDeclaration();
                        return m != null && MethodNode.overrides((CtMethod<?>) m, methodToMerge);
                    }
                });
        
        methodToMerge.setSimpleName(classPrefixedName(toMerge, methodToMerge));
        methodToMerge.setVisibility(PRIVATE);
        removeAnnotation(methodToMerge, Override.class);
        
        for (CtInvocation<T> superInvocation : superInvocations) {
            superInvocation.setTarget(null);
            superInvocation.setExecutable(methodToMerge.getReference());
        }
        add(mergeInto, methodToMerge, mergeInto::addMethod);
    }
    
    static <M extends CtElement> void add(CtClass<?> mergeInto, M member, Consumer<M> add) {
        add.accept(member);
        member.setParent(mergeInto);
    }

    static <A extends Annotation> void removeAnnotation(CtMethod<?> method, Class<A> annClass) {
        CtAnnotation<?> toRemove = null;
        for (CtAnnotation<? extends Annotation> ctAnnotation : method.getAnnotations()) {
            if (annClass.isAssignableFrom(ctAnnotation.getActualAnnotation().getClass())) {
                toRemove = ctAnnotation;
                break;
            }
        }
        if (toRemove != null)
            method.removeAnnotation(toRemove);
    }

    static String classPrefixedName(CtClass<?> ctClass, CtMethod<?> method) {
        return "_" + ctClass.getSimpleName() + "_" + method.getSimpleName();
    }
}
