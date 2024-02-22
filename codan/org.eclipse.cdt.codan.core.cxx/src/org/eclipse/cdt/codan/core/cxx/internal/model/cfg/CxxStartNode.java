/*******************************************************************************
 * Copyright (c) 2009, 2011 Alena Laskavaia
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *    Alena Laskavaia  - initial API and implementation
 *******************************************************************************/
package org.eclipse.cdt.codan.core.cxx.internal.model.cfg;

import org.eclipse.cdt.codan.internal.core.cfg.StartNode;

/**
 * TODO: add description
 */
public class CxxStartNode extends StartNode {
	/**
	 * @param next
	 */
	public CxxStartNode() {
		super();
	}

	@Override
	public String toString() {
		return "start"; //$NON-NLS-1$
	}
}
