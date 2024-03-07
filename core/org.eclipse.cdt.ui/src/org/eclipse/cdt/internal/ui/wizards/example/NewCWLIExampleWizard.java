/*******************************************************************************
 * Copyright (c) 2023 ChirpWow Technology Co., Ltd.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Arthur Hsiao - ChirpWow Tech.
 *******************************************************************************/
package org.eclipse.cdt.internal.ui.wizards.example;

import org.eclipse.cdt.internal.ui.CUIMessages;
import org.eclipse.tools.templates.ui.NewWizard;

public class NewCWLIExampleWizard extends NewWizard {

	private static final String cwliTag = "org.eclipse.cdt.ui.cwliTag"; //$NON-NLS-1$

	public NewCWLIExampleWizard() {
		super(cwliTag);
		setWindowTitle(CUIMessages.NewCWLIExampleWizard_Title);
		setTemplateSelectionPageTitle(CUIMessages.NewCWLIExampleWizard_PageTitle);
	}
}
