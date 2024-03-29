/*******************************************************************************
 * Copyright (c) 2009, 2015 Alena Laskavaia
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Alena Laskavaia  - initial API and implementation
 *     Tomasz Wesolowski - Bug 348387
 *     Sergey Prigogin (Google)
 *******************************************************************************/
package org.eclipse.cdt.codan.internal.checkers;

import java.util.Collection;
import java.util.Iterator;
import java.util.Stack;

import org.eclipse.cdt.codan.checkers.CodanCheckersActivator;
import org.eclipse.cdt.codan.core.cxx.CxxAstUtils;
import org.eclipse.cdt.codan.core.cxx.model.AbstractAstFunctionChecker;
import org.eclipse.cdt.codan.core.model.IProblem;
import org.eclipse.cdt.codan.core.model.IProblemWorkingCopy;
import org.eclipse.cdt.codan.core.model.cfg.IBasicBlock;
import org.eclipse.cdt.codan.core.model.cfg.ICfgData;
import org.eclipse.cdt.codan.core.model.cfg.IControlFlowGraph;
import org.eclipse.cdt.codan.core.model.cfg.IExitNode;
import org.eclipse.cdt.codan.internal.core.cfg.ControlFlowGraph;
import org.eclipse.cdt.core.dom.ast.ASTVisitor;
import org.eclipse.cdt.core.dom.ast.DOMException;
import org.eclipse.cdt.core.dom.ast.EScopeKind;
import org.eclipse.cdt.core.dom.ast.IASTCastExpression;
import org.eclipse.cdt.core.dom.ast.IASTCompoundStatement;
import org.eclipse.cdt.core.dom.ast.IASTConditionalExpression;
import org.eclipse.cdt.core.dom.ast.IASTDeclSpecifier;
import org.eclipse.cdt.core.dom.ast.IASTDeclaration;
import org.eclipse.cdt.core.dom.ast.IASTDeclarator;
import org.eclipse.cdt.core.dom.ast.IASTDoStatement;
import org.eclipse.cdt.core.dom.ast.IASTExpression;
import org.eclipse.cdt.core.dom.ast.IASTFieldReference;
import org.eclipse.cdt.core.dom.ast.IASTForStatement;
import org.eclipse.cdt.core.dom.ast.IASTFunctionDefinition;
import org.eclipse.cdt.core.dom.ast.IASTGotoStatement;
import org.eclipse.cdt.core.dom.ast.IASTIdExpression;
import org.eclipse.cdt.core.dom.ast.IASTIfStatement;
import org.eclipse.cdt.core.dom.ast.IASTInitializerClause;
import org.eclipse.cdt.core.dom.ast.IASTLabelStatement;
import org.eclipse.cdt.core.dom.ast.IASTPointerOperator;
import org.eclipse.cdt.core.dom.ast.IASTReturnStatement;
import org.eclipse.cdt.core.dom.ast.IASTSimpleDeclSpecifier;
import org.eclipse.cdt.core.dom.ast.IASTStatement;
import org.eclipse.cdt.core.dom.ast.IASTSwitchStatement;
import org.eclipse.cdt.core.dom.ast.IASTTypeId;
import org.eclipse.cdt.core.dom.ast.IASTUnaryExpression;
import org.eclipse.cdt.core.dom.ast.IASTWhileStatement;
import org.eclipse.cdt.core.dom.ast.IBasicType;
import org.eclipse.cdt.core.dom.ast.IBinding;
import org.eclipse.cdt.core.dom.ast.IFunction;
import org.eclipse.cdt.core.dom.ast.IParameter;
import org.eclipse.cdt.core.dom.ast.IPointerType;
import org.eclipse.cdt.core.dom.ast.IScope;
import org.eclipse.cdt.core.dom.ast.IType;
import org.eclipse.cdt.core.dom.ast.IVariable;
import org.eclipse.cdt.core.dom.ast.cpp.ICPPASTFunctionDefinition;
import org.eclipse.cdt.core.dom.ast.cpp.ICPPASTLambdaExpression;
import org.eclipse.cdt.core.dom.ast.cpp.ICPPASTReferenceOperator;
import org.eclipse.cdt.core.dom.ast.cpp.ICPPASTTryBlockStatement;
import org.eclipse.cdt.core.dom.ast.cpp.ICPPConstructor;
import org.eclipse.cdt.core.dom.ast.cpp.ICPPField;
import org.eclipse.cdt.core.dom.ast.cpp.ICPPMethod;
import org.eclipse.cdt.core.dom.ast.cpp.ICPPReferenceType;
import org.eclipse.cdt.core.parser.util.CharArrayUtils;
import org.eclipse.cdt.internal.core.dom.parser.cpp.semantics.CPPTemplates;
import org.eclipse.cdt.internal.core.dom.parser.cpp.semantics.SemanticUtil;

/**
 * The checker suppose to find issue related to mismatched return value/function
 * declaration<br>
 * <li>Function declared as returning non-void returns void
 * <li>Function declared as returning void has non-void return
 * <li>Function declared as returning non-void has no return (requires control flow graph)
 */
public class ReturnChecker extends AbstractAstFunctionChecker {
	public static final String PARAM_IMPLICIT = "implicit"; //$NON-NLS-1$
	public static final String RET_NO_VALUE_ID = "org.eclipse.cdt.codan.checkers.noreturn"; //$NON-NLS-1$
	public static final String RET_ERR_VALUE_ID = "org.eclipse.cdt.codan.checkers.errreturnvalue"; //$NON-NLS-1$
	public static final String RET_NORET_ID = "org.eclipse.cdt.codan.checkers.errnoreturn"; //$NON-NLS-1$
	public static final String RET_LOCAL_ID = "org.eclipse.cdt.codan.checkers.localvarreturn"; //$NON-NLS-1$

	private IType cachedReturnType = null;

	private enum RetType {
		BY_REF, BY_PTR
	}

	private class ReturnTypeAnalyzer {
		private RetType retType;
		private Stack<Integer> innermostOp;

		public ReturnTypeAnalyzer(RetType t) {
			retType = t;
			innermostOp = new Stack<>();
		}

		public RetType getType() {
			return retType;
		}

		public void visit(IASTInitializerClause expr) {
			if (expr instanceof IASTCastExpression) {
				visit((IASTCastExpression) expr);
			} else if (expr instanceof IASTConditionalExpression) {
				visit((IASTConditionalExpression) expr);
			} else if (expr instanceof IASTIdExpression) {
				visit((IASTIdExpression) expr);
			} else if (expr instanceof IASTUnaryExpression) {
				visit((IASTUnaryExpression) expr);
			} else if (expr instanceof IASTFieldReference) {
				visit((IASTFieldReference) expr);
			}
		}

		private void visit(IASTFieldReference expr) {
			visit(expr.getFieldOwner());
		}

		private void visit(IASTCastExpression expr) {
			IASTTypeId id = expr.getTypeId();
			IASTDeclarator declarator = id.getAbstractDeclarator();
			IASTPointerOperator[] ptr = declarator.getPointerOperators();
			if (ptr.length > 0 && ptr[0] instanceof ICPPASTReferenceOperator) {
				innermostOp.push(IASTUnaryExpression.op_amper);
			}
			visit(expr.getOperand());
			if (ptr.length > 0 && ptr[0] instanceof ICPPASTReferenceOperator) {
				innermostOp.pop();
			}
		}

		private void visit(IASTConditionalExpression expr) {
			visit(expr.getPositiveResultExpression());
			visit(expr.getNegativeResultExpression());
		}

		private void visit(IASTIdExpression expr) {
			IBinding binding = expr.getName().resolveBinding();
			if (binding instanceof IVariable && !(binding instanceof IParameter) && !(binding instanceof ICPPField)) {
				Integer op = null;
				if (!innermostOp.empty())
					op = innermostOp.peek();
				IType t = ((IVariable) binding).getType();
				if (((IVariable) binding).isStatic()) {
					return;
				}
				t = SemanticUtil.getNestedType(t, SemanticUtil.TDEF);
				if (retType == RetType.BY_REF && !(t instanceof ICPPReferenceType)) {
					if (t instanceof IPointerType && op != null && op == IASTUnaryExpression.op_star) {
						return;
					}
					try {
						IScope scope = binding.getScope();
						if (scope.getKind() == EScopeKind.eLocal) {
							reportProblem(RET_LOCAL_ID, expr, binding.getName());
						}
					} catch (DOMException e) {
						CodanCheckersActivator.log(e);
					}
				} else if (retType == RetType.BY_PTR && op != null && op == IASTUnaryExpression.op_amper) {
					try {
						IScope scope = binding.getScope();
						if (scope.getKind() == EScopeKind.eLocal) {
							reportProblem(RET_LOCAL_ID, expr, binding.getName());
						}
					} catch (DOMException e) {
						CodanCheckersActivator.log(e);
					}
				}
			}
		}

		private void visit(IASTUnaryExpression expr) {
			innermostOp.push(expr.getOperator());
			visit(expr.getOperand());
			innermostOp.pop();
		}
	}

	class ReturnStmpVisitor extends ASTVisitor {
		private final IASTFunctionDefinition func;
		private final ReturnTypeAnalyzer analyzer;
		boolean hasret;

		ReturnStmpVisitor(IASTFunctionDefinition func) {
			shouldVisitStatements = true;
			shouldVisitDeclarations = true;
			shouldVisitExpressions = true;
			this.func = func;
			this.hasret = false;
			IBinding binding = func.getDeclarator().getName().resolveBinding();
			if (binding instanceof IFunction) {
				IType retType = SemanticUtil.getNestedType(((IFunction) binding).getType().getReturnType(),
						SemanticUtil.TDEF);
				if (retType instanceof ICPPReferenceType) {
					analyzer = new ReturnTypeAnalyzer(RetType.BY_REF);
				} else if (retType instanceof IPointerType) {
					analyzer = new ReturnTypeAnalyzer(RetType.BY_PTR);
				} else
					analyzer = null;
			} else
				analyzer = null;
		}

		RetType getType() {
			if (analyzer != null)
				return analyzer.getType();
			return null;
		}

		@Override
		public int visit(IASTDeclaration element) {
			if (element != func)
				return PROCESS_SKIP; // skip inner functions
			return PROCESS_CONTINUE;
		}

		@Override
		public int visit(IASTExpression expr) {
			if (expr instanceof ICPPASTLambdaExpression) {
				return PROCESS_SKIP;
			}
			return PROCESS_CONTINUE;
		}

		@Override
		public int visit(IASTStatement stmt) {
			if (stmt instanceof IASTReturnStatement) {
				IASTReturnStatement ret = (IASTReturnStatement) stmt;
				IASTInitializerClause returnValue = ret.getReturnArgument();
				if (returnValue != null) {
					hasret = true;
				}
				ReturnTypeKind returnKind = getReturnTypeKind(func);
				if (returnKind == ReturnTypeKind.NonVoid && !isConstructorDestructor(func)) {
					if (checkImplicitReturn(RET_NO_VALUE_ID) || isExplicitReturn(func)) {
						if (returnValue == null)
							reportProblem(RET_NO_VALUE_ID, ret);
						else if (analyzer != null)
							analyzer.visit(returnValue);
					}
				} else if (returnKind == ReturnTypeKind.Void) {
					if (returnValue instanceof IASTExpression expr) {
						IType type = SemanticUtil.getNestedType(expr.getExpressionType(), SemanticUtil.TDEF);
						if (isVoid(type) || CPPTemplates.isDependentType(type)) {
							// For case of TypeOfDependentExpression see comment in getReturnTypeKind()
							return PROCESS_SKIP;
						}
						reportProblem(RET_ERR_VALUE_ID, returnValue);
					}
				}
				return PROCESS_SKIP;
			}
			return PROCESS_CONTINUE;
		}
	}

	private static boolean isConstructorDestructor(IASTFunctionDefinition func) {
		if (func instanceof ICPPASTFunctionDefinition) {
			IBinding method = func.getDeclarator().getName().resolveBinding();
			if (method instanceof ICPPConstructor
					|| method instanceof ICPPMethod && ((ICPPMethod) method).isDestructor()) {
				return true;
			}
		}
		return false;
	}

	public boolean isMain(IASTFunctionDefinition func) {
		try {
			char[] functionName = func.getDeclarator().getName().getSimpleID();
			if (CharArrayUtils.equals(functionName, "main")) { //$NON-NLS-1$
				return true;
			}
		} catch (RuntimeException e) {
			// Well, not main.
		}
		return false;
	}

	@Override
	protected void processFunction(IASTFunctionDefinition func) {
		cachedReturnType = null;
		ReturnStmpVisitor visitor = new ReturnStmpVisitor(func);
		func.accept(visitor);
		ReturnTypeKind returnKind = getReturnTypeKind(func);
		if (returnKind == ReturnTypeKind.NonVoid && !isMain(func)) {
			// There a return but maybe it is only on one branch.
			IASTStatement body = func.getBody();
			if (body instanceof IASTCompoundStatement) {
				IASTStatement[] statements = ((IASTCompoundStatement) body).getStatements();
				if (statements.length > 0) {
					IASTStatement last = statements[statements.length - 1];
					// Get nested statement if this is a label
					while (last instanceof IASTLabelStatement) {
						last = ((IASTLabelStatement) last).getNestedStatement();
					}
					// Now check if last statement if complex (for optimization reasons, building CFG is expensive).
					if (isCompoundStatement(last)) {
						if (endsWithNoExitNode(func))
							reportNoRet(func, visitor.hasret);
					} else if (!isFuncExitStatement(last)) {
						if (!isInDeadCode(func, last))
							reportNoRet(func, visitor.hasret);
					}
				} else {
					reportNoRet(func, false);
				}
			}
		}
	}

	private boolean isInDeadCode(IASTFunctionDefinition func, IASTStatement last) {
		Collection<IBasicBlock> deadBlocks = getDeadBlocks(func);
		for (Iterator<IBasicBlock> iterator = deadBlocks.iterator(); iterator.hasNext();) {
			IBasicBlock bb = iterator.next();
			if (((ICfgData) bb).getData() == last)
				return true;
		}
		return false;
	}

	// TODO: Any reason not to just expose getDeadNodes() in IControlFlowGraph?
	public Collection<IBasicBlock> getDeadBlocks(IASTFunctionDefinition func) {
		IControlFlowGraph graph = getModelCache().getControlFlowGraph(func);
		return ((ControlFlowGraph) graph).getDeadNodes();
	}

	protected void reportNoRet(IASTFunctionDefinition func, boolean hasRet) {
		if (!hasRet) {
			// No return at all.
			if (!checkImplicitReturn(RET_NORET_ID) && !isExplicitReturn(func)) {
				return;
			}
		}

		reportProblem(RET_NORET_ID, func.getDeclSpecifier());
	}

	private boolean isCompoundStatement(IASTStatement last) {
		return last instanceof IASTIfStatement || last instanceof IASTWhileStatement || last instanceof IASTDoStatement
				|| last instanceof IASTForStatement || last instanceof IASTSwitchStatement
				|| last instanceof IASTCompoundStatement || last instanceof ICPPASTTryBlockStatement
				|| last instanceof IASTGotoStatement;
	}

	protected boolean isFuncExitStatement(IASTStatement statement) {
		return statement instanceof IASTReturnStatement || CxxAstUtils.isThrowStatement(statement)
				|| CxxAstUtils.isExitStatement(statement);
	}

	/**
	 * @param id the problem id
	 * @return true if need to check inside functions with implicit return
	 */
	protected boolean checkImplicitReturn(String id) {
		final IProblem pt = getProblemById(id, getFile());
		return (Boolean) getPreference(pt, PARAM_IMPLICIT);
	}

	protected boolean endsWithNoExitNode(IASTFunctionDefinition func) {
		IControlFlowGraph graph = getModelCache().getControlFlowGraph(func);
		Iterator<IExitNode> exitNodeIterator = graph.getExitNodeIterator();
		for (; exitNodeIterator.hasNext();) {
			IExitNode node = exitNodeIterator.next();
			Object astNode = ((ICfgData) node).getData();
			if (astNode == null) {
				// If it real exit node such as return, exit or throw data will be an AST node,
				// if it is null it is a fake node added by the graph builder.
				Collection<IBasicBlock> deadBlocks = getDeadBlocks(func);
				if (!deadBlocks.contains(node)) // exit node is in dead code, not reporting Bug 350168
					return true;
			}
		}
		return false;
	}

	protected boolean isExplicitReturn(IASTFunctionDefinition func) {
		IASTDeclSpecifier declSpecifier = func.getDeclSpecifier();
		return !(declSpecifier instanceof IASTSimpleDeclSpecifier
				&& ((IASTSimpleDeclSpecifier) declSpecifier).getType() == IASTSimpleDeclSpecifier.t_unspecified);
	}

	enum ReturnTypeKind {
		Void, NonVoid, Unknown
	}

	/**
	 * Checks if the function has a return type other than void. Constructors and destructors
	 * don't have return type.
	 *
	 * @param func the function to check
	 * @return {@code true} if the function has a non void return type
	 */
	private ReturnTypeKind getReturnTypeKind(IASTFunctionDefinition func) {
		if (isConstructorDestructor(func))
			return ReturnTypeKind.Void;
		IType returnType = getReturnType(func);
		if (CPPTemplates.isDependentType(returnType)) {
			// Could instantiate to void or not.
			// If we care to, we could do some more heuristic analysis.
			// For example, if C is a class template, `C<T>` will always be non-void,
			// but `typename C<T>::type` is still unknown.
			return ReturnTypeKind.Unknown;
		}
		return isVoid(returnType) ? ReturnTypeKind.Void : ReturnTypeKind.NonVoid;
	}

	private IType getReturnType(IASTFunctionDefinition func) {
		if (cachedReturnType == null) {
			IType type = SemanticUtil.getNestedType(CxxAstUtils.getReturnType(func), SemanticUtil.TDEF);
			cachedReturnType = type;
		}
		return cachedReturnType;
	}

	private static boolean isVoid(IType type) {
		return type instanceof IBasicType && ((IBasicType) type).getKind() == IBasicType.Kind.eVoid;
	}

	@Override
	public void initPreferences(IProblemWorkingCopy problem) {
		super.initPreferences(problem);
		if (problem.getId().equals(RET_NO_VALUE_ID) || problem.getId().equals(RET_NORET_ID)) {
			addPreference(problem, PARAM_IMPLICIT, CheckersMessages.ReturnChecker_Param0, Boolean.FALSE);
		}
	}
}
