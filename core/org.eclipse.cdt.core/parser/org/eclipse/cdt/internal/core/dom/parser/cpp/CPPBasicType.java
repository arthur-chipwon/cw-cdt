/*******************************************************************************
 * Copyright (c) 2004, 2015 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Andrew Niefer (IBM Corporation) - initial API and implementation
 *     Markus Schorn (Wind River Systems)
 *     Sergey Prigogin (Google)
 *******************************************************************************/
package org.eclipse.cdt.internal.core.dom.parser.cpp;

import static org.eclipse.cdt.core.dom.ast.cpp.ICPPParameter.EMPTY_CPPPARAMETER_ARRAY;

import java.text.MessageFormat;

import org.eclipse.cdt.core.CCorePlugin;
import org.eclipse.cdt.core.dom.ast.ASTTypeUtil;
import org.eclipse.cdt.core.dom.ast.IASTExpression;
import org.eclipse.cdt.core.dom.ast.IASTLiteralExpression;
import org.eclipse.cdt.core.dom.ast.IASTSimpleDeclSpecifier;
import org.eclipse.cdt.core.dom.ast.IBasicType;
import org.eclipse.cdt.core.dom.ast.IScope;
import org.eclipse.cdt.core.dom.ast.IType;
import org.eclipse.cdt.core.dom.ast.ITypedef;
import org.eclipse.cdt.core.dom.ast.cpp.ICPPASTInitializerClause;
import org.eclipse.cdt.core.dom.ast.cpp.ICPPASTSimpleDeclSpecifier;
import org.eclipse.cdt.core.dom.ast.cpp.ICPPBasicType;
import org.eclipse.cdt.core.dom.ast.cpp.ICPPFunction;
import org.eclipse.cdt.internal.core.dom.parser.ISerializableType;
import org.eclipse.cdt.internal.core.dom.parser.ITypeMarshalBuffer;
import org.eclipse.cdt.internal.core.dom.parser.ValueFactory;
import org.eclipse.cdt.internal.core.dom.parser.cpp.semantics.CPPSemantics;
import org.eclipse.core.runtime.CoreException;

/**
 * Built-in c++ type.
 */
public class CPPBasicType implements ICPPBasicType, ISerializableType {
	public static final CPPBasicType BOOLEAN = new CPPBasicType(Kind.eBoolean, 0, null);
	public static final CPPBasicType NULL_PTR = new CPPBasicType(Kind.eNullPtr, 0, null);
	public static final CPPBasicType UNSPECIFIED_TYPE = new CPPBasicType(Kind.eUnspecified, 0);
	public static final CPPBasicType SHORT = new CPPBasicType(Kind.eInt, IBasicType.IS_SHORT);
	public static final CPPBasicType INT = new CPPBasicType(Kind.eInt, 0);
	public static final CPPBasicType LONG = new CPPBasicType(Kind.eInt, IBasicType.IS_LONG);
	public static final CPPBasicType LONG_LONG = new CPPBasicType(Kind.eInt, IBasicType.IS_LONG_LONG);
	public static final CPPBasicType INT128 = new CPPBasicType(Kind.eInt128, 0);
	public static final CPPBasicType UNSIGNED_SHORT = new CPPBasicType(Kind.eInt,
			IBasicType.IS_SHORT | IBasicType.IS_UNSIGNED);
	public static final CPPBasicType UNSIGNED_INT = new CPPBasicType(Kind.eInt, IBasicType.IS_UNSIGNED);
	public static final CPPBasicType UNSIGNED_LONG = new CPPBasicType(Kind.eInt,
			IBasicType.IS_LONG | IBasicType.IS_UNSIGNED);
	public static final CPPBasicType UNSIGNED_LONG_LONG = new CPPBasicType(Kind.eInt,
			IBasicType.IS_LONG_LONG | IBasicType.IS_UNSIGNED);
	public static final CPPBasicType UNSIGNED_INT128 = new CPPBasicType(Kind.eInt128, IBasicType.IS_UNSIGNED);
	public static final CPPBasicType CHAR = new CPPBasicType(Kind.eChar, 0);
	public static final CPPBasicType VOID = new CPPBasicType(Kind.eVoid, 0);

	public static final int FROM_LITERAL = 1 << 30;
	public static final int FROM_STRING_LITERAL = 1 << 31;

	private static final short TYPE_BUFFER_KIND_OFFSET = ITypeMarshalBuffer.FIRST_FLAG;
	private static final short TYPE_BUFFER_FROM_LITERAL_FLAG = ITypeMarshalBuffer.SECOND_LAST_FLAG / 2;
	private static final short TYPE_BUFFER_FIRST_FLAG_AFTER_KIND = TYPE_BUFFER_FROM_LITERAL_FLAG;
	private static final int MAX_KIND_INT_VALUE = (TYPE_BUFFER_FIRST_FLAG_AFTER_KIND - 1) / TYPE_BUFFER_KIND_OFFSET;

	private final Kind fKind;
	private int fModifiers;
	private Long fAssociatedValue;
	private ICPPFunction fPseudoDestructor;

	public CPPBasicType(Kind kind, int qualifiers, IASTExpression expression) {
		if (kind == Kind.eUnspecified) {
			if ((qualifiers & (IS_COMPLEX | IS_IMAGINARY)) != 0) {
				fKind = Kind.eFloat;
			} else if ((qualifiers & (IS_LONG | IS_SHORT | IS_SIGNED | IS_UNSIGNED | IS_LONG_LONG)) != 0) {
				fKind = Kind.eInt;
			} else {
				fKind = Kind.eUnspecified;
			}
		} else {
			fKind = kind;
		}
		if (expression instanceof IASTLiteralExpression) {
			qualifiers |= FROM_LITERAL;
			if (((IASTLiteralExpression) expression).getKind() == IASTLiteralExpression.lk_string_literal) {
				qualifiers |= FROM_STRING_LITERAL;
			}
		}
		fModifiers = qualifiers;
		if (expression instanceof ICPPASTInitializerClause) {
			Number num = ValueFactory.create(expression).numberValue();
			fAssociatedValue = num != null ? num.longValue() : null;
		}
	}

	public CPPBasicType(Kind kind, int qualifiers) {
		this(kind, qualifiers, null);
	}

	public CPPBasicType(ICPPASTSimpleDeclSpecifier sds) {
		this(getKind(sds), getModifiers(sds), null);
	}

	private static int getModifiers(ICPPASTSimpleDeclSpecifier sds) {
		return (sds.isLong() ? IBasicType.IS_LONG : 0) | (sds.isShort() ? IBasicType.IS_SHORT : 0)
				| (sds.isSigned() ? IBasicType.IS_SIGNED : 0) | (sds.isUnsigned() ? IBasicType.IS_UNSIGNED : 0)
				| (sds.isLongLong() ? IBasicType.IS_LONG_LONG : 0) | (sds.isComplex() ? IBasicType.IS_COMPLEX : 0)
				| (sds.isImaginary() ? IBasicType.IS_IMAGINARY : 0);
	}

	private static Kind getKind(ICPPASTSimpleDeclSpecifier sds) {
		return getKind(sds.getType());
	}

	static Kind getKind(final int simpleDeclSpecType) {
		// Note: when adding a new kind, marshal() and unnmarshal() may need to be revised.
		switch (simpleDeclSpecType) {
		case IASTSimpleDeclSpecifier.t_bool:
			return Kind.eBoolean;
		case IASTSimpleDeclSpecifier.t_char:
			return Kind.eChar;
		case IASTSimpleDeclSpecifier.t_wchar_t:
			return Kind.eWChar;
		case IASTSimpleDeclSpecifier.t_char8_t:
			return Kind.eChar8;
		case IASTSimpleDeclSpecifier.t_char16_t:
			return Kind.eChar16;
		case IASTSimpleDeclSpecifier.t_char32_t:
			return Kind.eChar32;
		case IASTSimpleDeclSpecifier.t_double:
			return Kind.eDouble;
		case IASTSimpleDeclSpecifier.t_float:
			return Kind.eFloat;
		case IASTSimpleDeclSpecifier.t_float128:
			return Kind.eFloat128;
		case IASTSimpleDeclSpecifier.t_decimal32:
			return Kind.eDecimal32;
		case IASTSimpleDeclSpecifier.t_decimal64:
			return Kind.eDecimal64;
		case IASTSimpleDeclSpecifier.t_decimal128:
			return Kind.eDecimal128;
		case IASTSimpleDeclSpecifier.t_int:
			return Kind.eInt;
		case IASTSimpleDeclSpecifier.t_int128:
			return Kind.eInt128;
		case IASTSimpleDeclSpecifier.t_void:
			return Kind.eVoid;
		default:
			return Kind.eUnspecified;
		}
	}

	@Override
	public boolean isSameType(IType object) {
		if (object == this)
			return true;

		if (object instanceof ITypedef)
			return object.isSameType(this);

		if (!(object instanceof ICPPBasicType))
			return false;

		ICPPBasicType other = (ICPPBasicType) object;
		if (fKind != other.getKind())
			return false;

		int modifiers = getModifiers();
		int otherModifiers = other.getModifiers();
		if (fKind == Kind.eInt) {
			// Signed int and int are equivalent.
			return (modifiers & ~IS_SIGNED) == (otherModifiers & ~IS_SIGNED);
		}
		return modifiers == otherModifiers;
	}

	@Override
	public Kind getKind() {
		return fKind;
	}

	@Override
	public boolean isSigned() {
		return (fModifiers & IS_SIGNED) != 0;
	}

	@Override
	public boolean isUnsigned() {
		return (fModifiers & IS_UNSIGNED) != 0;
	}

	@Override
	public boolean isShort() {
		return (fModifiers & IS_SHORT) != 0;
	}

	@Override
	public boolean isLong() {
		return (fModifiers & IS_LONG) != 0;
	}

	@Override
	public boolean isLongLong() {
		return (fModifiers & IS_LONG_LONG) != 0;
	}

	@Override
	public boolean isComplex() {
		return (fModifiers & IS_COMPLEX) != 0;
	}

	@Override
	public boolean isImaginary() {
		return (fModifiers & IS_IMAGINARY) != 0;
	}

	@Override
	public CPPBasicType clone() {
		return clone(~0);
	}

	/**
	 * Clone as normal but keep only requested flags.
	 *
	 * @param flagsMask The mask of flags to preserve during the clone.
	 */
	public CPPBasicType clone(int flagsMask) {
		CPPBasicType t = null;
		try {
			t = (CPPBasicType) super.clone();
			t.fModifiers &= flagsMask;
		} catch (CloneNotSupportedException e) {
			// Not going to happen.
		}
		return t;
	}

	/**
	 * Sets the numerical value this type was created for.
	 *
	 * @param value the numerical value of {@code null}
	 */
	public final void setAssociatedNumericalValue(Long value) {
		fAssociatedValue = value;
	}

	/**
	 * Returns the numerical value this type was created for, or {@code null}.
	 */
	public final Long getAssociatedNumericalValue() {
		return fAssociatedValue;
	}

	/**
	 * Returns {@code true} if the type was created from a string literal.
	 */
	public final boolean isFromStringLiteral() {
		return (fModifiers & FROM_STRING_LITERAL) != 0;
	}

	/**
	 * Returns {@code true} if the type was created from a literal.
	 */
	public final boolean isFromLiteral() {
		return (fModifiers & FROM_LITERAL) != 0;
	}

	@Override
	public final int getModifiers() {
		return fModifiers & ~FROM_STRING_LITERAL & ~FROM_LITERAL;
	}

	@Override
	public String toString() {
		return ASTTypeUtil.getType(this);
	}

	@Override
	public void marshal(ITypeMarshalBuffer buffer) throws CoreException {
		final int kind = getKind().ordinal();
		// 'kind' uses the space of the first few flags so make sure it doesn't overflow to the actual used flags further.
		if (kind > MAX_KIND_INT_VALUE) {
			throw new CoreException(CCorePlugin.createStatus(
					MessageFormat.format("Cannot marshal a basic type, kind ''{0}'' would overflow following flags.", //$NON-NLS-1$
							getKind().toString())));
		}
		final int shiftedKind = kind * TYPE_BUFFER_KIND_OFFSET;
		final int modifiers = getModifiers();
		short firstBytes = (short) (ITypeMarshalBuffer.BASIC_TYPE | shiftedKind);
		if (isFromLiteral())
			firstBytes = setFirstBytesFlag(firstBytes, TYPE_BUFFER_FROM_LITERAL_FLAG);
		if (fAssociatedValue != null)
			firstBytes = setFirstBytesFlag(firstBytes, ITypeMarshalBuffer.SECOND_LAST_FLAG);
		if (modifiers != 0)
			firstBytes = setFirstBytesFlag(firstBytes, ITypeMarshalBuffer.LAST_FLAG);
		buffer.putShort(firstBytes);
		if (modifiers != 0)
			buffer.putByte((byte) modifiers);
		if (fAssociatedValue != null)
			buffer.putLong(getAssociatedNumericalValue());

	}

	private static short setFirstBytesFlag(short firstBytes, short flag) throws CoreException {
		if (flag < TYPE_BUFFER_FIRST_FLAG_AFTER_KIND) {
			throw new CoreException(CCorePlugin.createStatus(
					MessageFormat.format("Cannot marshal a basic type, flag ''0x{0}'' overlaps ''kind'' bytes.", //$NON-NLS-1$
							Integer.toHexString(flag))));
		}
		return (short) (firstBytes | flag);
	}

	public static IType unmarshal(short firstBytes, ITypeMarshalBuffer buffer) throws CoreException {
		final boolean haveModifiers = (firstBytes & ITypeMarshalBuffer.LAST_FLAG) != 0;
		final boolean haveAssociatedNumericalValue = (firstBytes & ITypeMarshalBuffer.SECOND_LAST_FLAG) != 0;
		int modifiers = 0;
		int kind = (firstBytes & (TYPE_BUFFER_FIRST_FLAG_AFTER_KIND - 1)) / TYPE_BUFFER_KIND_OFFSET;
		if (haveModifiers)
			modifiers = buffer.getByte();
		if ((firstBytes & TYPE_BUFFER_FROM_LITERAL_FLAG) != 0)
			modifiers |= FROM_LITERAL;
		CPPBasicType result = new CPPBasicType(Kind.values()[kind], modifiers);
		if (haveAssociatedNumericalValue)
			result.setAssociatedNumericalValue(buffer.getLong());
		return result;
	}

	@Override
	@Deprecated
	public int getQualifierBits() {
		return getModifiers();
	}

	@Override
	@Deprecated
	public int getType() {
		switch (fKind) {
		case eBoolean:
			return t_bool;
		case eChar:
		case eChar8:
		case eChar16:
		case eChar32:
			return t_char;
		case eWChar:
			return t_wchar_t;
		case eDouble:
			return t_double;
		case eFloat:
			return t_float;
		case eInt:
			return t_int;
		case eVoid:
			return t_void;
		case eUnspecified:
			return t_unspecified;
		case eNullPtr:
		case eInt128:
		case eFloat128:
		case eDecimal32:
		case eDecimal64:
		case eDecimal128:
			// Null pointer type cannot be expressed wit ha simple decl specifier.
			break;
		}
		return t_unspecified;
	}

	/**
	 * @deprecated Types don't have values
	 */
	@Override
	@Deprecated
	public IASTExpression getValue() {
		return null;
	}

	@Override
	public ICPPFunction getPseudoDestructor() {
		if (fPseudoDestructor == null) {
			char[] dtorName = ("~" + toString()).toCharArray(); //$NON-NLS-1$
			IScope globalScope = CPPSemantics.getCurrentLookupPoint().getTranslationUnit().getScope();
			fPseudoDestructor = new CPPImplicitFunction(dtorName, globalScope, CPPClassScope.DESTRUCTOR_FUNCTION_TYPE,
					EMPTY_CPPPARAMETER_ARRAY, true, false);
		}
		return fPseudoDestructor;
	}
}
