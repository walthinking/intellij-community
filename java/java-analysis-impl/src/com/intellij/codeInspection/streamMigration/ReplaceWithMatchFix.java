/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.codeInspection.streamMigration;

import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.streamMigration.StreamApiMigrationInspection.InitializerUsageStatus;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.siyeh.ig.psiutils.BoolUtils;
import com.siyeh.ig.psiutils.ControlFlowUtils;
import com.siyeh.ig.psiutils.ExpressionUtils;
import com.siyeh.ig.psiutils.ParenthesesUtils;
import org.jetbrains.annotations.NotNull;

/**
 * @author Tagir Valeev
 */
class ReplaceWithMatchFix extends MigrateToStreamFix {
  private static final Logger LOG = Logger.getInstance("#" + ReplaceWithMatchFix.class.getName());

  private final String myMethodName;

  public ReplaceWithMatchFix(String methodName) {
    myMethodName = methodName;
  }

  @NotNull
  @Override
  public String getFamilyName() {
    return "Replace with " + myMethodName + "()";
  }

  @Override
  PsiElement migrate(@NotNull Project project,
               @NotNull ProblemDescriptor descriptor,
               @NotNull PsiForeachStatement foreachStatement,
               @NotNull PsiExpression iteratedValue,
               @NotNull PsiStatement body,
               @NotNull StreamApiMigrationInspection.TerminalBlock tb) {
    PsiElementFactory elementFactory = JavaPsiFacade.getElementFactory(project);
    if(tb.getSingleStatement() instanceof PsiReturnStatement) {
      PsiReturnStatement returnStatement = (PsiReturnStatement)tb.getSingleStatement();
      PsiExpression value = returnStatement.getReturnValue();
      if (ExpressionUtils.isLiteral(value, Boolean.TRUE) || ExpressionUtils.isLiteral(value, Boolean.FALSE)) {
        boolean foundResult = (boolean)((PsiLiteralExpression)value).getValue();
        PsiReturnStatement nextReturnStatement = StreamApiMigrationInspection.getNextReturnStatement(foreachStatement);
        if (nextReturnStatement != null) {
          PsiExpression returnValue = nextReturnStatement.getReturnValue();
          if(returnValue == null) return null;
          String methodName = foundResult ? "anyMatch" : "noneMatch";
          String streamText = generateStream(iteratedValue, tb.getLastOperation()).toString();
          streamText = addTerminalOperation(streamText, methodName, foreachStatement, tb);
          restoreComments(foreachStatement, body);
          if (nextReturnStatement.getParent() == foreachStatement.getParent()) {
            if(!ExpressionUtils.isLiteral(returnValue, !foundResult)) {
              streamText+= (foundResult ? "||" : "&&") + ParenthesesUtils.getText(returnValue, ParenthesesUtils.AND_PRECEDENCE);
            }
            removeLoop(foreachStatement);
            return returnValue.replace(elementFactory.createExpressionFromText(streamText, nextReturnStatement));
          }
          return foreachStatement.replace(elementFactory.createStatementFromText("return " + streamText + ";", foreachStatement));
        }
      }
    }
    PsiStatement[] statements = tb.getStatements();
    if (!(statements.length == 1 || (statements.length == 2 && ControlFlowUtils.statementBreaksLoop(statements[1], foreachStatement)))) {
      return null;
    }
    restoreComments(foreachStatement, body);
    String streamText = generateStream(iteratedValue, tb.getLastOperation()).toString();
    streamText = addTerminalOperation(streamText, "anyMatch", foreachStatement, tb);
    PsiStatement statement = statements[0];
    PsiAssignmentExpression assignment = ExpressionUtils.getAssignment(statement);
    if(assignment != null) {
      PsiExpression lValue = assignment.getLExpression();
      PsiExpression rValue = assignment.getRExpression();
      if (!(lValue instanceof PsiReferenceExpression) || rValue == null) return null;
      PsiElement maybeVar = ((PsiReferenceExpression)lValue).resolve();
      if(maybeVar instanceof PsiVariable) {
        // Simplify single assignments like this:
        // boolean flag = false;
        // for(....) if(...) {flag = true; break;}
        PsiVariable var = (PsiVariable)maybeVar;
        PsiExpression initializer = var.getInitializer();
        InitializerUsageStatus status = StreamApiMigrationInspection.getInitializerUsageStatus(var, foreachStatement);
        if(initializer != null && status != InitializerUsageStatus.UNKNOWN) {
          String replacement;
          if(ExpressionUtils.isLiteral(initializer, Boolean.FALSE) &&
             ExpressionUtils.isLiteral(rValue, Boolean.TRUE)) {
            replacement = streamText;
          } else if(ExpressionUtils.isLiteral(initializer, Boolean.TRUE) &&
                    ExpressionUtils.isLiteral(rValue, Boolean.FALSE)) {
            replacement = "!"+streamText;
          } else {
            replacement = streamText + "?" + rValue.getText() + ":" + initializer.getText();
          }
          return replaceInitializer(foreachStatement, var, initializer, replacement, status);
        }
      }
    }
    String replacement = "if(" + streamText + "){" + statement.getText() + "}";
    return foreachStatement.replace(elementFactory.createStatementFromText(replacement, foreachStatement));
  }

  private static String addTerminalOperation(String origStream, String methodName, @NotNull PsiElement contextElement,
                                             @NotNull StreamApiMigrationInspection.TerminalBlock tb) {
    PsiElementFactory elementFactory = JavaPsiFacade.getElementFactory(contextElement.getProject());
    PsiExpression stream = elementFactory.createExpressionFromText(origStream, contextElement);
    LOG.assertTrue(stream instanceof PsiMethodCallExpression);
    PsiElement nameElement = ((PsiMethodCallExpression)stream).getMethodExpression().getReferenceNameElement();
    if (nameElement != null && nameElement.getText().equals("filter")) {
      if (methodName.equals("noneMatch")) {
        // Try to reduce noneMatch(x -> !(condition)) to allMatch(x -> condition)
        PsiExpression[] expressions = ((PsiMethodCallExpression)stream).getArgumentList().getExpressions();
        if (expressions.length == 1 && expressions[0] instanceof PsiLambdaExpression) {
          PsiLambdaExpression lambda = (PsiLambdaExpression)expressions[0];
          PsiElement lambdaBody = lambda.getBody();
          if (lambdaBody instanceof PsiExpression && BoolUtils.isNegation((PsiExpression)lambdaBody)) {
            PsiExpression negated = BoolUtils.getNegated((PsiExpression)lambdaBody);
            LOG.assertTrue(negated != null, lambdaBody.getText());
            lambdaBody.replace(negated);
            methodName = "allMatch";
          }
        }
      }
      nameElement.replace(elementFactory.createIdentifier(methodName));
      return stream.getText();
    }
    return origStream + "." + methodName + "(" + tb.getVariable().getName() + " -> true)";
  }
}
