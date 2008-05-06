/*
 * Copyright 2006 Sascha Weinreuter
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.intellij.plugins.intelliLang.util;

import com.intellij.codeInsight.AnnotationUtil;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.*;
import com.intellij.psi.impl.PsiConstantEvaluationHelperImpl;
import com.intellij.psi.impl.compiled.ClsLiteralExpressionImpl;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Contains some extended utility functions for dealing with annotations.
 */
public class AnnotationUtilEx {
  private static final PsiConstantEvaluationHelperImpl CONSTANT_EVALUATION_HELPER = new PsiConstantEvaluationHelperImpl();

  private AnnotationUtilEx() {
  }

  /**
   * @see AnnotationUtilEx#getAnnotatedElementFor(com.intellij.psi.PsiExpression,
   *      org.intellij.plugins.intelliLang.util.AnnotationUtilEx.LookupType)
   */
  public enum LookupType {
    PREFER_CONTEXT, PREFER_DECLARATION, CONTEXT_ONLY, DECLRARATION_ONLY
  }

  /**
   * Determines the PsiModifierListOwner for the passed element depending of the specified LookupType. The LookupType
   * decides whether to prefer the element a reference expressions resolves to, or the element that is implied by the
   * usage context ("expected type").
   */
  @Nullable
  public static PsiModifierListOwner getAnnotatedElementFor(@Nullable PsiExpression element, LookupType type) {
    if (element == null) return null;

    if (type == LookupType.PREFER_DECLARATION || type == LookupType.DECLRARATION_ONLY) {
      if (element instanceof PsiReferenceExpression) {
        final PsiElement e = ((PsiReferenceExpression)element).resolve();
        if (e instanceof PsiModifierListOwner) {
          return (PsiModifierListOwner)e;
        }
        if (type == LookupType.DECLRARATION_ONLY) {
          return null;
        }
      }
    }

    final PsiElement parent = element.getParent();

    if (parent instanceof PsiAssignmentExpression) {
      final PsiAssignmentExpression p = ((PsiAssignmentExpression)parent);
      if (p.getRExpression() == element) {
        return getAnnotatedElementFor(p.getLExpression(), type);
      }
    }
    else if (parent instanceof PsiExpression) {
      return getAnnotatedElementFor((PsiExpression)parent, type);
    }
    else if (parent instanceof PsiReturnStatement) {
      final PsiMethod m = PsiTreeUtil.getParentOfType(parent, PsiMethod.class);
      if (m != null) {
        return m;
      }
    }
    else if (parent instanceof PsiModifierListOwner) {
      return (PsiModifierListOwner)parent;
    }
    else if (parent instanceof PsiArrayInitializerMemberValue) {
      final PsiArrayInitializerMemberValue value = (PsiArrayInitializerMemberValue)parent;
      final PsiElement pair = value.getParent();
      if (pair instanceof PsiNameValuePair) {
        return getAnnotationMethod(((PsiNameValuePair)pair), element);
      }
    }
    else if (parent instanceof PsiNameValuePair) {
      return getAnnotationMethod(((PsiNameValuePair)parent), element);
    }
    else {
      return PsiUtilEx.getParameterForArgument(element);
    }

    // If no annotation has been found through the usage context, check if the element
    // (i.e. the element the reference refers to) is annotated itself
    if (type != LookupType.DECLRARATION_ONLY) {
      if (element instanceof PsiReferenceExpression) {
        final PsiElement e = ((PsiReferenceExpression)element).resolve();
        if (e instanceof PsiModifierListOwner) {
          return (PsiModifierListOwner)e;
        }
      }
    }

    return null;
  }

  @Nullable
  private static PsiModifierListOwner getAnnotationMethod(PsiNameValuePair pair, PsiExpression element) {
    final PsiAnnotation annotation = PsiTreeUtil.getParentOfType(pair.getParent(), PsiAnnotation.class);
    assert annotation != null;

    final String fqn = annotation.getQualifiedName();
    assert fqn != null;

    final PsiClass psiClass = JavaPsiFacade.getInstance(element.getProject()).findClass(fqn, element.getResolveScope());
    if (psiClass != null && psiClass.isAnnotationType()) {
      final String name = pair.getName();
      final PsiMethod[] methods = psiClass.findMethodsByName(name != null ? name : "value", false);
      return methods.length > 0 ? methods[0] : null;
    }
    return null;
  }

  /**
   * Utility method to obtain annotations of a specific type from the supplied PsiModifierListOwner.
   * For optimization reasons, this method only looks at elements of type java.lang.String.
   * <p/>
   * The parameter <code>allowIndirect</code> determines if the method should look for indirect annotations, i.e.
   * annotations which have themselves been annotated by the supplied annotation name. Currently, this only allows
   * one level of indirection and returns an array of [base-annotation, indirect annotation]
   * <p/>
   * The <code>annotationName</code> parameter is a pair of the target annotation class' fully qualified name as a
   * String and as a Set. This is done for performance reasons because the Set is required by the
   * {@link com.intellij.codeInsight.AnnotationUtil} utility class and allows to avoid unecessary object constructions.
   */
  @NotNull
  public static PsiAnnotation[] getAnnotationFrom(PsiModifierListOwner owner,
                                                  Pair<String, ? extends Set<String>> annotationName,
                                                  boolean allowIndirect,
                                                  boolean inHierarchy) {
    if (owner instanceof PsiMethod) {
      final PsiType returnType = ((PsiMethod)owner).getReturnType();
      if (returnType == null || !PsiUtilEx.isStringOrStringArray(returnType)) {
        return PsiAnnotation.EMPTY_ARRAY;
      }
    }
    else if (owner instanceof PsiVariable) {
      final PsiType type = ((PsiVariable)owner).getType();
      if (!PsiUtilEx.isStringOrStringArray(type)) {
        return PsiAnnotation.EMPTY_ARRAY;
      }
    }
    else {
      return PsiAnnotation.EMPTY_ARRAY;
    }

    if (AnnotationUtil.isAnnotated(owner, annotationName.first, inHierarchy)) {
      final PsiAnnotation annotation = AnnotationUtil.findAnnotationInHierarchy(owner, annotationName.second);
      assert annotation != null;

      return new PsiAnnotation[]{annotation};
    }
    else if (allowIndirect) {
      final PsiAnnotation[] annotations = getAnnotations(owner, inHierarchy);
      for (PsiAnnotation annotation : annotations) {
        final String fqn = annotation.getQualifiedName();
        if (fqn == null) {
          continue;
        }
        final PsiClass psiClass = JavaPsiFacade.getInstance(owner.getProject()).findClass(fqn, annotation.getResolveScope());
        if (psiClass != null) {
          final PsiAnnotation psiAnnotation = AnnotationUtil.findAnnotationInHierarchy(psiClass, annotationName.second);
          if (psiAnnotation != null) {
            return new PsiAnnotation[]{psiAnnotation, annotation};
          }
        }
      }
    }
    return PsiAnnotation.EMPTY_ARRAY;
  }

  public static PsiAnnotation[] getAnnotationFrom(@NotNull PsiModifierListOwner owner,
                                                  @NotNull Pair<String, ? extends Set<String>> annotationName,
                                                  boolean allowIndirect) {
    return getAnnotationFrom(owner, annotationName, allowIndirect, true);
  }

  /**
   * Calculates the value of the annotation's attribute referenced by the <code>attr</code> parameter by trying to
   * find the attribute in the supplied list of annotations and calculating the constant value for the first attribute
   * it finds.
   */
  @Nullable
  public static String calcAnnotationValue(PsiAnnotation annotation[], @NonNls String attr) {
    for (PsiAnnotation psiAnnotation : annotation) {
      final String value = calcAnnotationValue(psiAnnotation, attr);
      if (value != null) return value;
    }
    return null;
  }

  @Nullable
  public static String calcAnnotationValue(@NotNull PsiAnnotation annotation, @NonNls String attr) {
    PsiElement value = annotation.findAttributeValue(attr);
    final Object o;
    if (value instanceof ClsLiteralExpressionImpl) {
      final ClsLiteralExpressionImpl expr = ((ClsLiteralExpressionImpl)value);
      if (expr.getValue() == null) {
        // This happens when the expression isn't a simple literal but e.g. a concatenated string and the
        // string contains characters that must be escaped: "A" + "\\w" + "B" -> "A\wB" which isn't a legal
        // string literal any more -> value == null (IDEA-10001)
        try {
          final String s = expr.getText();
          if (s.startsWith("\"") && s.endsWith("\"")) {
            final String e = "\"" + StringUtil.escapeStringCharacters(s.substring(1, s.length() - 1)) + "\"";
            value = JavaPsiFacade.getInstance(annotation.getProject()).getElementFactory().createExpressionFromText(e, annotation);
          }
        }
        catch (IncorrectOperationException e) {
          Logger.getInstance(AnnotationUtilEx.class.getName()).error(e);
        }
      }
    }
    if (value instanceof PsiExpression) {
      o = CONSTANT_EVALUATION_HELPER.computeConstantExpression((PsiExpression)value);
    }
    else {
      return null;
    }
    if (o instanceof String) {
      return (String)o;
    }
    return null;
  }

  /**
   * Returns all annotations for <code>listOwner</code>, possibly walking up the method hierarchy.
   *
   * @see com.intellij.codeInsight.AnnotationUtil#isAnnotated(com.intellij.psi.PsiModifierListOwner, java.lang.String, boolean)
   */
  public static PsiAnnotation[] getAnnotations(@NotNull PsiModifierListOwner listOwner, boolean inHierarchy) {
    if (listOwner instanceof PsiParameter) {
      // this is more efficient than getting the modifier list
      return ((PsiParameter)listOwner).getAnnotations();
    }
    final PsiModifierList modifierList = listOwner.getModifierList();
    if (modifierList == null) {
      return PsiAnnotation.EMPTY_ARRAY;
    }
    if (inHierarchy && listOwner instanceof PsiMethod) {
      final Set<PsiAnnotation> all = new HashSet<PsiAnnotation>() {
        public boolean add(PsiAnnotation o) {
          // don't overwrite "higher level" annotations
          return !contains(o) && super.add(o);
        }
      };
      all.addAll(Arrays.asList(modifierList.getAnnotations()));
      addSuperAnnotations(all, (PsiMethod)listOwner);
      return all.toArray(new PsiAnnotation[all.size()]);
    }
    else {
      return modifierList.getAnnotations();
    }
  }

  private static void addSuperAnnotations(Set<PsiAnnotation> annotations, PsiMethod method) {
    final PsiMethod[] superMethods = method.findSuperMethods();
    for (PsiMethod superMethod : superMethods) {
      final PsiModifierList modifierList = superMethod.getModifierList();
      annotations.addAll(Arrays.asList(modifierList.getAnnotations()));
      addSuperAnnotations(annotations, superMethod);
    }
  }
}
