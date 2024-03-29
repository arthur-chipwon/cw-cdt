/*******************************************************************************
 * Copyright (c) 2007, 2015 Wind River Systems, Inc. and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Markus Schorn - initial API and implementation
 *     Sergey Prigogin (Google)
 *******************************************************************************/
package org.eclipse.cdt.internal.core.dom.parser;

import java.util.Optional;

import org.eclipse.cdt.core.dom.ast.IASTArrayModifier;
import org.eclipse.cdt.core.dom.ast.IASTArraySubscriptExpression;
import org.eclipse.cdt.core.dom.ast.IASTBinaryExpression;
import org.eclipse.cdt.core.dom.ast.IASTCaseStatement;
import org.eclipse.cdt.core.dom.ast.IASTCastExpression;
import org.eclipse.cdt.core.dom.ast.IASTCompoundStatement;
import org.eclipse.cdt.core.dom.ast.IASTConditionalExpression;
import org.eclipse.cdt.core.dom.ast.IASTDeclSpecifier;
import org.eclipse.cdt.core.dom.ast.IASTDeclarator;
import org.eclipse.cdt.core.dom.ast.IASTDoStatement;
import org.eclipse.cdt.core.dom.ast.IASTEqualsInitializer;
import org.eclipse.cdt.core.dom.ast.IASTExpression;
import org.eclipse.cdt.core.dom.ast.IASTExpressionList;
import org.eclipse.cdt.core.dom.ast.IASTExpressionStatement;
import org.eclipse.cdt.core.dom.ast.IASTFieldReference;
import org.eclipse.cdt.core.dom.ast.IASTForStatement;
import org.eclipse.cdt.core.dom.ast.IASTFunctionCallExpression;
import org.eclipse.cdt.core.dom.ast.IASTIdExpression;
import org.eclipse.cdt.core.dom.ast.IASTIfStatement;
import org.eclipse.cdt.core.dom.ast.IASTImplicitName;
import org.eclipse.cdt.core.dom.ast.IASTImplicitNameOwner;
import org.eclipse.cdt.core.dom.ast.IASTInitializerClause;
import org.eclipse.cdt.core.dom.ast.IASTInitializerList;
import org.eclipse.cdt.core.dom.ast.IASTNode;
import org.eclipse.cdt.core.dom.ast.IASTProblemExpression;
import org.eclipse.cdt.core.dom.ast.IASTProblemStatement;
import org.eclipse.cdt.core.dom.ast.IASTReturnStatement;
import org.eclipse.cdt.core.dom.ast.IASTStatement;
import org.eclipse.cdt.core.dom.ast.IASTSwitchStatement;
import org.eclipse.cdt.core.dom.ast.IASTTypeIdExpression;
import org.eclipse.cdt.core.dom.ast.IASTUnaryExpression;
import org.eclipse.cdt.core.dom.ast.IASTWhileStatement;
import org.eclipse.cdt.core.dom.ast.IArrayType;
import org.eclipse.cdt.core.dom.ast.IBinding;
import org.eclipse.cdt.core.dom.ast.IFunction;
import org.eclipse.cdt.core.dom.ast.IFunctionType;
import org.eclipse.cdt.core.dom.ast.IType;
import org.eclipse.cdt.core.dom.ast.IVariable;
import org.eclipse.cdt.core.dom.ast.cpp.ICPPASTFunctionDeclarator.RefQualifier;
import org.eclipse.cdt.core.dom.ast.cpp.ICPPASTInitializerClause;
import org.eclipse.cdt.core.dom.ast.cpp.ICPPASTRangeBasedForStatement;
import org.eclipse.cdt.core.dom.ast.cpp.ICPPASTStructuredBindingDeclaration;
import org.eclipse.cdt.core.dom.ast.gnu.IGNUASTCompoundStatementExpression;
import org.eclipse.cdt.core.parser.util.ArrayUtil;
import org.eclipse.cdt.internal.core.pdom.dom.PDOMName;

/**
 * Helper class to determine whether a variable is accessed for reading and/or writing.
 * The algorithm works starting from the variable and looking upwards what's being done
 * with the variable. C- and C++ specific things are handled in sub-classes.
 */
public abstract class VariableReadWriteFlags {
	protected static final int READ = PDOMName.READ_ACCESS;
	protected static final int WRITE = PDOMName.WRITE_ACCESS;

	/**
	 * Check if the access could be a write access taking into
	 * account the "unknown" state.
	 * @param flags The flags
	 * @return True if either write or unknown access detected, false otherwise
	 */
	public static boolean mayBeWriteAccess(Optional<Integer> flags) {
		if (!flags.isPresent() || (flags.get() & WRITE) != 0)
			return true;
		return false;
	}

	protected Optional<Integer> rwAnyNode(IASTNode node, int indirection) {
		final IASTNode parent = node.getParent();
		if (parent instanceof IASTExpression) {
			return rwInExpression((IASTExpression) parent, node, indirection);
		} else if (parent instanceof IASTStatement) {
			return rwInStatement((IASTStatement) parent, node, indirection);
		} else if (parent instanceof IASTDeclarator) {
			return rwInDeclarator((IASTDeclarator) parent, indirection);
		} else if (parent instanceof IASTEqualsInitializer) {
			return rwInEqualsInitializer((IASTEqualsInitializer) parent, indirection);
		} else if (parent instanceof IASTArrayModifier) {
			return Optional.of(READ); // dimension
		} else if (parent instanceof IASTInitializerList) {
			return rwInInitializerList((IASTInitializerList) parent, indirection);
		} else if (parent instanceof IASTDeclSpecifier) {
			return Optional.of(READ);
		}
		return Optional.empty(); // fallback
	}

	protected Optional<Integer> rwInDeclarator(IASTDeclarator parent, int indirection) {
		if (parent.getInitializer() != null)
			return Optional.of(WRITE);
		return Optional.of(0);
	}

	protected Optional<Integer> rwInEqualsInitializer(IASTEqualsInitializer parent, int indirection) {
		IASTNode grand = parent.getParent();
		if (grand instanceof IASTDeclarator) {
			IBinding binding = ((IASTDeclarator) grand).getName().getBinding();
			if (binding instanceof IVariable) {
				return rwAssignmentToType(((IVariable) binding).getType(), indirection);
			}
		} else if (grand instanceof ICPPASTStructuredBindingDeclaration) {
			return rwInStructuredBinding((ICPPASTStructuredBindingDeclaration) grand);
		}
		return Optional.empty(); // fallback
	}

	protected Optional<Integer> rwInInitializerList(IASTInitializerList parent, int indirection) {
		IASTNode grand = parent.getParent();
		if (grand instanceof IASTEqualsInitializer) {
			IASTNode grandGrand = grand.getParent();
			if (grandGrand instanceof IASTDeclarator) {
				IBinding binding = ((IASTDeclarator) grandGrand).getName().resolveBinding();
				if (binding instanceof IVariable) {
					IType type = ((IVariable) binding).getType();
					if (type instanceof IArrayType) {
						return rwAssignmentToType(type, indirection);
					}
				}
			}
		} else if (grand instanceof ICPPASTStructuredBindingDeclaration) {
			return rwInStructuredBinding((ICPPASTStructuredBindingDeclaration) grand);
		}
		return Optional.empty(); // fallback
	}

	protected Optional<Integer> rwInStructuredBinding(ICPPASTStructuredBindingDeclaration declaration) {
		RefQualifier refQualifier = declaration.getRefQualifier();
		if (refQualifier != null && !declaration.getDeclSpecifier().isConst()) {
			return Optional.of(READ | WRITE);
		} else {
			return Optional.of(READ);
		}
	}

	private boolean isAssignment(IASTBinaryExpression node) {
		switch (node.getOperator()) {
		case IASTBinaryExpression.op_assign:
		case IASTBinaryExpression.op_binaryAndAssign:
		case IASTBinaryExpression.op_binaryOrAssign:
		case IASTBinaryExpression.op_binaryXorAssign:
		case IASTBinaryExpression.op_divideAssign:
		case IASTBinaryExpression.op_minusAssign:
		case IASTBinaryExpression.op_moduloAssign:
		case IASTBinaryExpression.op_multiplyAssign:
		case IASTBinaryExpression.op_plusAssign:
		case IASTBinaryExpression.op_shiftLeftAssign:
		case IASTBinaryExpression.op_shiftRightAssign:
			return true;
		}
		return false;
	}

	protected Optional<Integer> rwInExpression(IASTExpression expr, IASTNode node, int indirection) {
		if (expr instanceof IASTIdExpression) {
			return rwAnyNode(expr, indirection);
		}
		if (expr instanceof IASTBinaryExpression) {
			return rwInBinaryExpression(node, (IASTBinaryExpression) expr, indirection);
		}
		if (expr instanceof IASTFieldReference) {
			return rwInFieldReference(node, (IASTFieldReference) expr, indirection);
		}
		if (expr instanceof IASTCastExpression) { // must be ahead of unary
			return rwAnyNode(expr, indirection);
		}
		if (expr instanceof IASTUnaryExpression) {
			return rwInUnaryExpression(node, (IASTUnaryExpression) expr, indirection);
		}
		if (expr instanceof IASTArraySubscriptExpression) {
			if (indirection > 0 && node.getPropertyInParent() == IASTArraySubscriptExpression.ARRAY) {
				return rwAnyNode(expr, indirection - 1);
			}
			if (expr.getParent() instanceof IASTBinaryExpression
					&& expr.getPropertyInParent() == IASTBinaryExpression.OPERAND_ONE
					&& isAssignment((IASTBinaryExpression) expr.getParent())) {
				return Optional.of(READ | WRITE);
			}
			return Optional.of(READ);
		}
		if (expr instanceof IASTConditionalExpression) {
			if (node.getPropertyInParent() == IASTConditionalExpression.LOGICAL_CONDITION) {
				return Optional.of(READ);
			}
			return rwAnyNode(expr, indirection);
		}
		if (expr instanceof IASTExpressionList) {
			// Only the first expression is passed on.
			final IASTExpression[] expressions = ((IASTExpressionList) expr).getExpressions();
			if (expressions.length > 0 && expressions[0] == node) {
				return rwAnyNode(expr, indirection);
			}
			return Optional.of(0);
		}
		if (expr instanceof IASTFunctionCallExpression) {
			if (node.getPropertyInParent() == IASTFunctionCallExpression.FUNCTION_NAME) {
				return rwInFunctionName((IASTExpression) node);
			}
			return rwArgumentForFunctionCall((IASTFunctionCallExpression) expr, node, indirection);
		}
		if (expr instanceof IASTProblemExpression) {
			return Optional.of(READ | WRITE);
		}
		if (expr instanceof IASTTypeIdExpression) {
			return Optional.of(0);
		}

		return Optional.empty(); // fall back
	}

	protected Optional<Integer> rwInFieldReference(IASTNode node, IASTFieldReference expr, int indirection) {
		if (node.getPropertyInParent() == IASTFieldReference.FIELD_NAME) {
			if (expr.getPropertyInParent() != IASTFunctionCallExpression.FUNCTION_NAME)
				return rwAnyNode(expr, indirection);
		} else { // IASTFieldReference.FIELD_OWNER
			if (expr.isPointerDereference())
				--indirection;
			if (indirection >= 0)
				return rwAnyNode(expr, indirection);
		}
		return Optional.of(READ);
	}

	protected Optional<Integer> rwInFunctionName(IASTExpression node) {
		return Optional.of(READ);
	}

	/**
	 * Helper method to take the union of two sets of read/write flags.
	 * Note that "unknown" (represented as Optional.empty()) unioned
	 * with anything is still "unknown", since "unknown" means that
	 * potentially any type of access is possible.
	 */
	protected Optional<Integer> union(Optional<Integer> a, Optional<Integer> b) {
		if (a.isPresent() && b.isPresent()) {
			return Optional.of(a.get() | b.get());
		}
		return Optional.empty();
	}

	protected Optional<Integer> rwArgumentForFunctionCall(final IASTFunctionCallExpression funcCall, IASTNode argument,
			int indirection) {
		final IASTInitializerClause[] args = funcCall.getArguments();
		int index = ArrayUtil.indexOf(args, argument);
		if (index == -1) {
			return Optional.empty();
		}
		final IASTExpression functionNameExpression = funcCall.getFunctionNameExpression();
		if (functionNameExpression == null) {
			return Optional.empty();
		}
		IType type = functionNameExpression.getExpressionType();
		IFunctionType functionType = null;
		if (type instanceof IFunctionType) {
			functionType = (IFunctionType) type;
		} else if (funcCall instanceof IASTImplicitNameOwner) {
			IASTImplicitName[] implicitNames = ((IASTImplicitNameOwner) funcCall).getImplicitNames();
			if (implicitNames.length == 1) {
				IASTImplicitName name = implicitNames[0];
				IBinding binding = name.resolveBinding();
				if (binding instanceof IFunction) {
					functionType = ((IFunction) binding).getType();
				}
			}
		}
		if (functionType == null) {
			return Optional.empty();
		}
		return rwArgumentForFunctionCall(functionType, index, args[index], indirection);
	}

	private IType getArgumentType(IASTInitializerClause argument) {
		if (argument instanceof ICPPASTInitializerClause) {
			return ((ICPPASTInitializerClause) argument).getEvaluation().getType();
		} else if (argument instanceof IASTExpression) {
			return ((IASTExpression) argument).getExpressionType();
		}
		return null;
	}

	protected Optional<Integer> rwArgumentForFunctionCall(IFunctionType type, int parameterIdx,
			IASTInitializerClause argument, int indirection) {
		IType[] ptypes = type.getParameterTypes();
		IType parameterType = null;
		if (ptypes != null && ptypes.length > parameterIdx) {
			parameterType = ptypes[parameterIdx];
		} else if (type.takesVarArgs()) {
			// Since variadic functions take their arguments by value, synthesize a parameter type
			// equal to the argument type.
			parameterType = getArgumentType(argument);
		}

		if (parameterType != null) {
			return rwAssignmentToType(parameterType, indirection);
		}
		return Optional.empty(); // Fallback
	}

	protected abstract Optional<Integer> rwAssignmentToType(IType type, int indirection);

	protected Optional<Integer> rwInStatement(IASTStatement stmt, IASTNode node, int indirection) {
		if (stmt instanceof IASTCaseStatement) {
			if (node.getPropertyInParent() == IASTCaseStatement.EXPRESSION) {
				return Optional.of(READ);
			}
		} else if (stmt instanceof IASTDoStatement) {
			if (node.getPropertyInParent() == IASTDoStatement.CONDITION) {
				return Optional.of(READ);
			}
		} else if (stmt instanceof IASTExpressionStatement) {
			IASTNode parent = stmt.getParent();
			while (parent instanceof IASTCompoundStatement) {
				IASTCompoundStatement compound = (IASTCompoundStatement) parent;
				IASTStatement[] statements = compound.getStatements();
				if (statements[statements.length - 1] != stmt) {
					return Optional.of(0);
				}
				stmt = compound;
				parent = stmt.getParent();
			}
			if (parent instanceof IGNUASTCompoundStatementExpression) {
				return rwAnyNode(parent, indirection);
			}
		} else if (stmt instanceof IASTForStatement) {
			if (node.getPropertyInParent() == IASTForStatement.CONDITION) {
				return Optional.of(READ);
			}
		} else if (stmt instanceof ICPPASTRangeBasedForStatement) {
			if (node.getPropertyInParent() == ICPPASTRangeBasedForStatement.INITIALIZER) {
				return Optional.of(READ);
			}
		} else if (stmt instanceof IASTIfStatement) {
			if (node.getPropertyInParent() == IASTIfStatement.CONDITION) {
				return Optional.of(READ);
			}
		} else if (stmt instanceof IASTProblemStatement) {
			return Optional.empty();
		} else if (stmt instanceof IASTReturnStatement) {
			return indirection == 0 ? Optional.of(READ) : Optional.of(WRITE);
		} else if (stmt instanceof IASTSwitchStatement) {
			if (node.getPropertyInParent() == IASTSwitchStatement.CONTROLLER_EXP) {
				return Optional.of(READ);
			}
		} else if (stmt instanceof IASTWhileStatement) {
			if (node.getPropertyInParent() == IASTWhileStatement.CONDITIONEXPRESSION) {
				return Optional.of(READ);
			}
		}
		return Optional.of(0);
	}

	protected Optional<Integer> rwInUnaryExpression(IASTNode node, IASTUnaryExpression expr, int indirection) {
		switch (expr.getOperator()) {
		case IASTUnaryExpression.op_bracketedPrimary:
			return rwAnyNode(expr, indirection);

		case IASTUnaryExpression.op_amper:
			return rwAnyNode(expr, indirection + 1);

		case IASTUnaryExpression.op_star:
			if (indirection > 0) {
				return rwAnyNode(expr, indirection - 1);
			}
			return Optional.of(READ);

		case IASTUnaryExpression.op_postFixDecr:
		case IASTUnaryExpression.op_postFixIncr:
		case IASTUnaryExpression.op_prefixDecr:
		case IASTUnaryExpression.op_prefixIncr:
			return Optional.of(READ | WRITE);

		case IASTUnaryExpression.op_minus:
		case IASTUnaryExpression.op_not:
		case IASTUnaryExpression.op_plus:
		case IASTUnaryExpression.op_tilde:
			return Optional.of(READ);

		case IASTUnaryExpression.op_sizeof:
		case IASTUnaryExpression.op_sizeofParameterPack:
		case IASTUnaryExpression.op_alignOf:
			return Optional.of(0);
		}
		return Optional.of(READ);
	}

	protected Optional<Integer> rwInBinaryExpression(IASTNode node, IASTBinaryExpression expr, int indirection) {
		switch (expr.getOperator()) {
		case IASTBinaryExpression.op_assign:
			if (node.getPropertyInParent() == IASTBinaryExpression.OPERAND_ONE) {
				return Optional.of(WRITE);
			}
			return rwAssignmentToType(expr.getOperand1().getExpressionType(), indirection);

		case IASTBinaryExpression.op_binaryAndAssign:
		case IASTBinaryExpression.op_binaryOrAssign:
		case IASTBinaryExpression.op_binaryXorAssign:
		case IASTBinaryExpression.op_divideAssign:
		case IASTBinaryExpression.op_minusAssign:
		case IASTBinaryExpression.op_moduloAssign:
		case IASTBinaryExpression.op_multiplyAssign:
		case IASTBinaryExpression.op_plusAssign:
		case IASTBinaryExpression.op_shiftLeftAssign:
		case IASTBinaryExpression.op_shiftRightAssign:
			if (node.getPropertyInParent() == IASTBinaryExpression.OPERAND_ONE) {
				return Optional.of(READ | WRITE);
			}
			return Optional.of(READ);

		case IASTBinaryExpression.op_binaryAnd:
		case IASTBinaryExpression.op_binaryOr:
		case IASTBinaryExpression.op_binaryXor:
		case IASTBinaryExpression.op_divide:
		case IASTBinaryExpression.op_equals:
		case IASTBinaryExpression.op_greaterEqual:
		case IASTBinaryExpression.op_greaterThan:
		case IASTBinaryExpression.op_lessEqual:
		case IASTBinaryExpression.op_lessThan:
		case IASTBinaryExpression.op_logicalAnd:
		case IASTBinaryExpression.op_logicalOr:
		case IASTBinaryExpression.op_modulo:
		case IASTBinaryExpression.op_multiply:
		case IASTBinaryExpression.op_notequals:
		case IASTBinaryExpression.op_shiftLeft:
		case IASTBinaryExpression.op_shiftRight:
		case IASTBinaryExpression.op_threewaycomparison:
			return Optional.of(READ);

		case IASTBinaryExpression.op_minus:
		case IASTBinaryExpression.op_plus:
			if (indirection > 0) {
				// can be pointer arithmetics
				return rwAnyNode(expr, indirection);
			}
			return Optional.of(READ);
		}
		return Optional.of(READ); // fallback
	}
}
