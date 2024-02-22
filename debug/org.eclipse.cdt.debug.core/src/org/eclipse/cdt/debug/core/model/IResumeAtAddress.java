/*******************************************************************************
 * Copyright (c) 2004, 2009 QNX Software Systems and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 * QNX Software Systems - Initial API and implementation
 *******************************************************************************/
package org.eclipse.cdt.debug.core.model;

import org.eclipse.cdt.core.IAddress;
import org.eclipse.debug.core.DebugException;

/**
 * Provides the ability to resume a debug target at the given address.
 * @since 6.0
 */
public interface IResumeAtAddress {

	/**
	 * Returns whether this operation is currently available for this element.
	 *
	 * @return whether this operation is currently available
	 */
	public boolean canResumeAtAddress(IAddress address);

	/**
	 * Causes this element to resume the execution at the specified address.
	 *
	 * @exception DebugException on failure. Reasons include:
	 */
	public void resumeAtAddress(IAddress address) throws DebugException;
}
