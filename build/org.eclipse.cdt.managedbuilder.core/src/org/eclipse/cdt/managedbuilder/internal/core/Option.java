/*******************************************************************************
 *  Copyright (c) 2003, 2017 IBM Corporation and others.
 *
 *  This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License 2.0
 *  which accompanies this distribution, and is available at
 *  https://www.eclipse.org/legal/epl-2.0/
 *
 *  SPDX-License-Identifier: EPL-2.0
 *
 *  Contributors:
 *     IBM - Initial API and implementation
 *     ARM Ltd. - basic tooltip support
 *     Petri Tuononen - [321040] Get Library Search Paths
 *     Baltasar Belyavsky (Texas Instruments) - [279633] Custom command-generator support
 *     cartu38 opendev (STMicroelectronics) - [514385] Custom defaultValue-generator support
 *******************************************************************************/
package org.eclipse.cdt.managedbuilder.internal.core;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.cdt.core.settings.model.ICStorageElement;
import org.eclipse.cdt.internal.core.SafeStringInterner;
import org.eclipse.cdt.managedbuilder.core.BuildException;
import org.eclipse.cdt.managedbuilder.core.IBuildObject;
import org.eclipse.cdt.managedbuilder.core.IBuildPropertiesRestriction;
import org.eclipse.cdt.managedbuilder.core.IConfiguration;
import org.eclipse.cdt.managedbuilder.core.IHoldsOptions;
import org.eclipse.cdt.managedbuilder.core.IManagedConfigElement;
import org.eclipse.cdt.managedbuilder.core.IManagedOptionValueHandler;
import org.eclipse.cdt.managedbuilder.core.IOption;
import org.eclipse.cdt.managedbuilder.core.IOptionApplicability;
import org.eclipse.cdt.managedbuilder.core.IOptionCategory;
import org.eclipse.cdt.managedbuilder.core.IOptionCommandGenerator;
import org.eclipse.cdt.managedbuilder.core.IOptionDefaultValueGenerator;
import org.eclipse.cdt.managedbuilder.core.IProjectType;
import org.eclipse.cdt.managedbuilder.core.ITool;
import org.eclipse.cdt.managedbuilder.core.IToolChain;
import org.eclipse.cdt.managedbuilder.core.ManagedBuildManager;
import org.eclipse.cdt.managedbuilder.core.ManagedBuilderCorePlugin;
import org.eclipse.cdt.managedbuilder.core.ManagedOptionValueHandler;
import org.eclipse.cdt.managedbuilder.core.OptionStringValue;
import org.eclipse.cdt.managedbuilder.internal.enablement.OptionEnablementExpression;
import org.eclipse.cdt.managedbuilder.internal.macros.OptionContextData;
import org.eclipse.cdt.managedbuilder.macros.IOptionContextData;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.osgi.framework.Version;

public class Option extends BuildObject implements IOption, IBuildPropertiesRestriction {
	private static final String IS_BUILTIN_EMPTY = "IS_BUILTIN_EMPTY"; //$NON-NLS-1$
	private static final String IS_VALUE_EMPTY = "IS_VALUE_EMPTY"; //$NON-NLS-1$
	// Static default return values
	public static final String EMPTY_STRING = ""; //$NON-NLS-1$
	public static final String[] EMPTY_STRING_ARRAY = new String[0];
	public static final OptionStringValue[] EMPTY_LV_ARRAY = new OptionStringValue[0];

	//  Superclass
	private IOption superClass;
	private String superClassId;
	//  Parent and children
	private IHoldsOptions holder;
	//  Managed Build model attributes
	private String unusedChildren;
	private Integer browseType;
	private String browseFilterPath;
	private String[] browseFilterExtensions;
	private List<OptionStringValue> builtIns;
	private IOptionCategory category;
	private String categoryId;
	private String command;
	private IConfigurationElement commandGeneratorElement;
	private IOptionCommandGenerator commandGenerator;
	private String commandFalse;
	private Boolean isForScannerDiscovery;
	private Boolean isExcludedFromScannerDiscovery;
	private String tip;
	private String contextId;
	private List<String> applicableValuesList;
	private Map<String, String> commandsMap;
	private Map<String, String> namesMap;
	private Object value;
	private Object defaultValue;
	private IConfigurationElement defaultValueGeneratorElement;
	private IOptionDefaultValueGenerator defaultValueGenerator;
	private Integer valueType;
	private Boolean isAbstract;
	private Integer resourceFilter;
	private IConfigurationElement valueHandlerElement = null;
	private IManagedOptionValueHandler valueHandler = null;
	private String valueHandlerExtraArgument;
	private String fieldEditorId;
	private String fieldEditorExtraArgument;
	private IConfigurationElement applicabilityCalculatorElement = null;
	private IOptionApplicability applicabilityCalculator = null;
	private BooleanExpressionApplicabilityCalculator booleanExpressionCalculator = null;
	private ITreeRoot treeRoot;
	//  Miscellaneous
	private boolean isExtensionOption = false;
	private boolean isDirty = false;
	private boolean resolved = true;
	private boolean verified = false;

	/**
	 * False for options which are invalid. getOption()
	 * routines will ignore invalid options.
	 */
	private boolean isValid = true;

	/**
	 * True for options which are created because of an
	 * MBS 2.0 model OptionReference element
	 */
	private boolean wasOptRef = false;
	private boolean isUdjusted = false;
	private boolean rebuildState;

	/**
	 * This constructor is called to create an option defined by an extension point in
	 * a plugin manifest file, or returned by a dynamic element provider
	 *
	 * @param parent  The IHoldsOptions parent of this option, or <code>null</code> if
	 *                defined at the top level
	 * @param element The option definition from the manifest file or a dynamic element
	 *                provider
	 */
	public Option(IHoldsOptions parent, IManagedConfigElement element) {
		this.holder = parent;
		isExtensionOption = true;

		// setup for resolving
		resolved = false;

		loadFromManifest(element);

		// Hook me up to the Managed Build Manager
		ManagedBuildManager.addExtensionOption(this);
	}

	/**
	 * This constructor is called to create an Option whose attributes and children will be
	 * added by separate calls.
	 *
	 * @param parent - the parent of the option, if any
	 * @param superClass - the superClass, if any
	 * @param Id - the id for the new option
	 * @param name - the name for the new option
	 * @param isExtensionElement - indicates whether this is an extension element or a managed project element
	 */
	public Option(IHoldsOptions parent, IOption superClass, String Id, String name, boolean isExtensionElement) {
		this.holder = parent;
		this.superClass = superClass;
		if (this.superClass != null) {
			superClassId = this.superClass.getId();
		}
		setId(Id);
		setName(name);
		isExtensionOption = isExtensionElement;
		if (isExtensionElement) {
			// Hook me up to the Managed Build Manager
			ManagedBuildManager.addExtensionOption(this);
		} else {
			setDirty(true);
			setRebuildState(true);
		}
	}

	/**
	 * Create an <code>Option</code> based on the specification stored in the
	 * project file (.cdtbuild).
	 *
	 * @param parent The <code>IHoldsOptions</code> the option will be added to.
	 * @param element The XML element that contains the option settings.
	 */
	public Option(IHoldsOptions parent, ICStorageElement element) {
		this.holder = parent;
		isExtensionOption = false;

		// Initialize from the XML attributes
		loadFromProject(element);
	}

	/**
	 * Create an <code>Option</code> based upon an existing option.
	 *
	 * @param parent The <code>IHoldsOptions</code> the option will be added to.
	 * @param Id     New ID for the option.
	 * @param name   New name for the option.
	 * @param option The existing option to clone, except for the above fields.
	 */
	public Option(IHoldsOptions parent, String Id, String name, Option option) {
		this.holder = parent;
		superClass = option.superClass;
		if (superClass != null) {
			superClassId = option.superClass.getId();
		} else if (option.superClassId != null) {
			superClassId = option.superClassId;
		}
		setId(Id);
		setName(name);
		isExtensionOption = false;
		boolean copyIds = Id.equals(option.id);

		//  Copy the remaining attributes
		if (option.unusedChildren != null) {
			unusedChildren = option.unusedChildren;
		}
		if (option.isAbstract != null) {
			isAbstract = option.isAbstract;
		}
		if (option.command != null) {
			command = option.command;
		}
		if (option.commandFalse != null) {
			commandFalse = option.commandFalse;
		}
		if (option.isForScannerDiscovery != null) {
			isForScannerDiscovery = option.isForScannerDiscovery;
		}
		if (option.isExcludedFromScannerDiscovery != null) {
			isExcludedFromScannerDiscovery = option.isExcludedFromScannerDiscovery;
		}
		if (option.tip != null) {
			tip = option.tip;
		}
		if (option.contextId != null) {
			contextId = option.contextId;
		}
		if (option.categoryId != null) {
			categoryId = option.categoryId;
		}
		if (option.builtIns != null) {
			builtIns = new ArrayList<>(option.builtIns);
		}
		if (option.browseType != null) {
			browseType = option.browseType;
		}
		if (option.browseFilterPath != null) {
			browseFilterPath = option.browseFilterPath;
		}
		if (option.browseFilterExtensions != null) {
			browseFilterExtensions = option.browseFilterExtensions.clone();
		}
		if (option.resourceFilter != null) {
			resourceFilter = option.resourceFilter;
		}
		if (option.applicableValuesList != null) {
			applicableValuesList = new ArrayList<>(option.applicableValuesList);
			commandsMap = new HashMap<>(option.commandsMap);
			namesMap = new HashMap<>(option.namesMap);
		}
		if (option.treeRoot != null) {
			treeRoot = new TreeRoot((TreeRoot) option.treeRoot);
		}

		if (option.valueType != null) {
			valueType = option.valueType;
		}
		try {
			int vType = option.getValueType();
			switch (vType) {
			case BOOLEAN:
				if (option.value != null) {
					value = option.value;
				}
				if (option.defaultValue != null) {
					defaultValue = option.defaultValue;
				}
				break;
			case STRING:
			case ENUMERATED:
			case TREE:
				if (option.value != null) {
					value = option.value;
				}
				if (option.defaultValue != null) {
					defaultValue = option.defaultValue;
				}
				break;
			case STRING_LIST:
			case INCLUDE_PATH:
			case PREPROCESSOR_SYMBOLS:
			case LIBRARIES:
			case OBJECTS:
			case INCLUDE_FILES:
			case LIBRARY_PATHS:
			case LIBRARY_FILES:
			case MACRO_FILES:
			case UNDEF_INCLUDE_PATH:
			case UNDEF_PREPROCESSOR_SYMBOLS:
			case UNDEF_INCLUDE_FILES:
			case UNDEF_LIBRARY_PATHS:
			case UNDEF_LIBRARY_FILES:
			case UNDEF_MACRO_FILES:
				if (option.value != null) {
					@SuppressWarnings("unchecked")
					ArrayList<OptionStringValue> list = new ArrayList<>((ArrayList<OptionStringValue>) option.value);
					value = list;
				}
				if (option.defaultValue != null) {
					@SuppressWarnings("unchecked")
					ArrayList<OptionStringValue> list = new ArrayList<>(
							(ArrayList<OptionStringValue>) option.defaultValue);
					defaultValue = list;
				}
				break;
			}
		} catch (BuildException be) {
			// TODO: should we ignore this??
		}

		category = option.category;

		defaultValueGeneratorElement = option.defaultValueGeneratorElement;
		defaultValueGenerator = option.defaultValueGenerator;

		commandGeneratorElement = option.commandGeneratorElement;
		commandGenerator = option.commandGenerator;

		applicabilityCalculatorElement = option.applicabilityCalculatorElement;
		applicabilityCalculator = option.applicabilityCalculator;

		booleanExpressionCalculator = option.booleanExpressionCalculator;

		if (option.valueHandlerElement != null) {
			valueHandlerElement = option.valueHandlerElement;
			valueHandler = option.valueHandler;
		}
		if (option.valueHandlerExtraArgument != null) {
			valueHandlerExtraArgument = option.valueHandlerExtraArgument;
		}

		if (option.fieldEditorId != null) {
			fieldEditorId = option.fieldEditorId;
		}
		if (option.fieldEditorExtraArgument != null) {
			fieldEditorExtraArgument = option.fieldEditorExtraArgument;
		}

		if (copyIds) {
			isDirty = option.isDirty;
			rebuildState = option.rebuildState;
		} else {
			setDirty(true);
			setRebuildState(true);
		}
	}

	/*
	 *  E L E M E N T   A T T R I B U T E   R E A D E R S   A N D   W R I T E R S
	 */

	/**
	 * Loads the option information from the ManagedConfigElement specified in the
	 * argument.
	 *
	 * @param element Contains the option information
	 */
	protected void loadFromManifest(IManagedConfigElement element) {
		ManagedBuildManager.putConfigElement(this, element);

		// id
		setId(SafeStringInterner.safeIntern(element.getAttribute(IBuildObject.ID)));

		// Get the name
		setName(SafeStringInterner.safeIntern(element.getAttribute(IBuildObject.NAME)));

		// superClass
		superClassId = SafeStringInterner.safeIntern(element.getAttribute(IProjectType.SUPERCLASS));

		// Get the unused children, if any
		unusedChildren = SafeStringInterner.safeIntern(element.getAttribute(IProjectType.UNUSED_CHILDREN));

		// isAbstract
		String isAbs = element.getAttribute(IProjectType.IS_ABSTRACT);
		if (isAbs != null) {
			isAbstract = Boolean.parseBoolean(isAbs);
		}

		// Get the defaultValue-generator, if any
		String defaultValueGeneratorStr = element.getAttribute(DEFAULTVALUE_GENERATOR);
		if (defaultValueGeneratorStr != null && element instanceof DefaultManagedConfigElement) {
			defaultValueGeneratorElement = ((DefaultManagedConfigElement) element).getConfigurationElement();
		}

		// Get the command defined for the option
		command = SafeStringInterner.safeIntern(element.getAttribute(COMMAND));

		// Get the command-generator, if any
		String commandGeneratorStr = element.getAttribute(COMMAND_GENERATOR);
		if (commandGeneratorStr != null && element instanceof DefaultManagedConfigElement) {
			commandGeneratorElement = ((DefaultManagedConfigElement) element).getConfigurationElement();
		}

		// Get the command defined for a Boolean option when the value is False
		commandFalse = SafeStringInterner.safeIntern(element.getAttribute(COMMAND_FALSE));

		// isForScannerDiscovery
		String isForSD = element.getAttribute(USE_BY_SCANNER_DISCOVERY);
		if (isForSD != null) {
			isForScannerDiscovery = Boolean.parseBoolean(isForSD);
		}

		// isNotForScannerDiscovery
		String isExcludeFromSD = element.getAttribute(EXCLUDE_FROM_SCANNER_DISCOVERY);
		if (isExcludeFromSD != null) {
			isExcludedFromScannerDiscovery = Boolean.parseBoolean(isExcludeFromSD);
		}

		// Get the tooltip for the option
		tip = SafeStringInterner.safeIntern(element.getAttribute(TOOL_TIP));

		// Get the contextID for the option
		contextId = SafeStringInterner.safeIntern(element.getAttribute(CONTEXT_ID));

		// Options hold different types of values
		String valueTypeStr = element.getAttribute(VALUE_TYPE);
		if (valueTypeStr != null) {
			valueType = Integer.valueOf(ValueTypeStrToInt(valueTypeStr));
		}

		// Note: The value and defaultValue attributes are loaded in the resolveReferences routine.
		//       This is because we need to have the value-type, and this may be defined in a
		//       superClass that is not yet loaded.

		// Determine if there needs to be a browse button
		String browseTypeStr = element.getAttribute(BROWSE_TYPE);
		if (browseTypeStr == null) {
			// Set to null, to indicate no browse type specification
			// This will allow any superclasses to be searched for the
			// browse type specification, and thus inherited, if found,
			// which they should be
			browseType = null;
		} else if (browseTypeStr.equals(NONE)) {
			browseType = BROWSE_NONE;
		} else if (browseTypeStr.equals(FILE)) {
			browseType = BROWSE_FILE;
		} else if (browseTypeStr.equals(DIR)) {
			browseType = BROWSE_DIR;
		}

		// Get the browseFilterPath attribute
		this.browseFilterPath = SafeStringInterner.safeIntern(element.getAttribute(BROWSE_FILTER_PATH));

		// Get the browseFilterExtensions attribute
		String browseFilterExtensionsStr = element.getAttribute(BROWSE_FILTER_EXTENSIONS);
		if (browseFilterExtensionsStr != null) {
			this.browseFilterExtensions = SafeStringInterner.safeIntern(browseFilterExtensionsStr.split("\\s*,\\s*")); //$NON-NLS-1$
		}

		categoryId = SafeStringInterner.safeIntern(element.getAttribute(CATEGORY));

		// Get the resourceFilter attribute
		String resFilterStr = element.getAttribute(RESOURCE_FILTER);
		if (resFilterStr == null) {
			// Set to null, to indicate no resource filter specification
			// This will allow any superclasses to be searched for the
			// resource filter specification, and thus inherited, if found,
			// which they should be
			resourceFilter = null;
		} else if (resFilterStr.equals(ALL)) {
			resourceFilter = FILTER_ALL;
		} else if (resFilterStr.equals(FILE)) {
			resourceFilter = FILTER_FILE;
		} else if (resFilterStr.equals(PROJECT)) {
			resourceFilter = FILTER_PROJECT;
		}

		//get enablements
		IManagedConfigElement enablements[] = element.getChildren(OptionEnablementExpression.NAME);
		if (enablements.length > 0) {
			booleanExpressionCalculator = new BooleanExpressionApplicabilityCalculator(enablements);
		}

		// get the applicability calculator, if any
		String applicabilityCalculatorStr = element.getAttribute(APPLICABILITY_CALCULATOR);
		if (applicabilityCalculatorStr != null && element instanceof DefaultManagedConfigElement) {
			applicabilityCalculatorElement = ((DefaultManagedConfigElement) element).getConfigurationElement();
		} else {
			applicabilityCalculator = booleanExpressionCalculator;
		}

		// valueHandler
		// Store the configuration element IFF there is a value handler defined
		String valueHandler = element.getAttribute(VALUE_HANDLER);
		if (valueHandler != null && element instanceof DefaultManagedConfigElement) {
			valueHandlerElement = ((DefaultManagedConfigElement) element).getConfigurationElement();
		}
		// valueHandlerExtraArgument
		valueHandlerExtraArgument = SafeStringInterner.safeIntern(element.getAttribute(VALUE_HANDLER_EXTRA_ARGUMENT));

		// fieldEditor and optional argument
		fieldEditorId = element.getAttribute(FIELD_EDITOR_ID);
		fieldEditorExtraArgument = element.getAttribute(FIELD_EDITOR_EXTRA_ARGUMENT);
	}

	/**
	 * Initialize the option information from the XML element
	 * specified in the argument
	 *
	 * @param element An XML element containing the option information
	 */
	protected void loadFromProject(ICStorageElement element) {

		// id (unique, don't intern)
		setId(element.getAttribute(IBuildObject.ID));

		// name
		if (element.getAttribute(IBuildObject.NAME) != null) {
			setName(SafeStringInterner.safeIntern(element.getAttribute(IBuildObject.NAME)));
		}

		// superClass
		superClassId = SafeStringInterner.safeIntern(element.getAttribute(IProjectType.SUPERCLASS));
		if (superClassId != null && superClassId.length() > 0) {
			superClass = ManagedBuildManager.getExtensionOption(superClassId);
			if (superClass == null) {
				/*
				 * This can happen when options are set at the resource level, for a project using a toolchain definition
				 * where there are options at the toolchain level & one or more of those options is set at a
				 * non-default value.
				 *
				 * In these cases the superclass is set to the option from the parent not the extension's ID
				 * Workaround this by searching for any missing superclass IDs at on the parent configs toolchain
				 *
				 * See the "bug580009.tests.cfg1.tc" definition in org.eclipse.cdt.managedbuilder.core.tests for an example
				 */
				IBuildObject parent = this.getParent();
				if (parent instanceof IToolChain) {
					IConfiguration config = ((IToolChain) parent).getParent();
					IOption foundOption = null;
					//In rare cases the RootFolderInfo may not have loaded & will cause an NPE
					if (config != null && config.getRootFolderInfo() != null) {
						IToolChain parentToolchain = config.getToolChain();
						if (parentToolchain != null) {
							foundOption = parentToolchain.getOptionById(superClassId);
						}
					}
					if (foundOption != null) {
						superClass = foundOption;
					} else {
						ManagedBuilderCorePlugin.log(new Status(IStatus.ERROR, ManagedBuilderCorePlugin.PLUGIN_ID,
								MessageFormat.format("Missing superclass \"{0}\" for \"{1}\"", superClassId, getId()))); //$NON-NLS-1$
					}
				} else {
					ManagedBuilderCorePlugin.log(new Status(IStatus.ERROR, ManagedBuilderCorePlugin.PLUGIN_ID,
							MessageFormat.format("Missing superclass \"{0}\" for \"{1}\"", superClassId, getId()))); //$NON-NLS-1$
				}
			}
		}

		// Get the unused children, if any
		if (element.getAttribute(IProjectType.UNUSED_CHILDREN) != null) {
			unusedChildren = SafeStringInterner.safeIntern(element.getAttribute(IProjectType.UNUSED_CHILDREN));
		}

		// isAbstract
		if (element.getAttribute(IProjectType.IS_ABSTRACT) != null) {
			String isAbs = element.getAttribute(IProjectType.IS_ABSTRACT);
			if (isAbs != null) {
				isAbstract = Boolean.parseBoolean(isAbs);
			}
		}

		// Get the command defined for the option
		if (element.getAttribute(COMMAND) != null) {
			command = SafeStringInterner.safeIntern(element.getAttribute(COMMAND));
		}

		// Get the command defined for a Boolean option when the value is False
		if (element.getAttribute(COMMAND_FALSE) != null) {
			commandFalse = SafeStringInterner.safeIntern(element.getAttribute(COMMAND_FALSE));
		}

		// isForScannerDiscovery
		if (element.getAttribute(USE_BY_SCANNER_DISCOVERY) != null) {
			String isForSD = element.getAttribute(USE_BY_SCANNER_DISCOVERY);
			if (isForSD != null) {
				isForScannerDiscovery = Boolean.parseBoolean(isForSD);
			}
		}

		// isNotForScannerDiscovery
		if (element.getAttribute(EXCLUDE_FROM_SCANNER_DISCOVERY) != null) {
			String isExcludeFromSD = element.getAttribute(EXCLUDE_FROM_SCANNER_DISCOVERY);
			if (isExcludeFromSD != null) {
				isForScannerDiscovery = Boolean.parseBoolean(isExcludeFromSD);
			}
		}

		// Get the tooltip for the option
		if (element.getAttribute(TOOL_TIP) != null) {
			tip = SafeStringInterner.safeIntern(element.getAttribute(TOOL_TIP));
		}

		// Get the contextID for the option
		if (element.getAttribute(CONTEXT_ID) != null) {
			contextId = SafeStringInterner.safeIntern(element.getAttribute(CONTEXT_ID));
		}

		// Options hold different types of values
		if (element.getAttribute(VALUE_TYPE) != null) {
			String valueTypeStr = element.getAttribute(VALUE_TYPE);
			valueType = Integer.valueOf(ValueTypeStrToInt(valueTypeStr));
		}

		// Now get the actual value based upon value-type
		try {
			int valType = getValueType();
			switch (valType) {
			case BOOLEAN:
				// Convert the string to a boolean
				if (element.getAttribute(VALUE) != null) {
					value = Boolean.valueOf(element.getAttribute(VALUE));
				}
				if (element.getAttribute(DEFAULT_VALUE) != null) {
					defaultValue = Boolean.valueOf(element.getAttribute(DEFAULT_VALUE));
				}
				break;
			case STRING:
				// Just get the value out of the option directly
				if (element.getAttribute(VALUE) != null) {
					value = SafeStringInterner.safeIntern(element.getAttribute(VALUE));
				}
				if (element.getAttribute(DEFAULT_VALUE) != null) {
					defaultValue = SafeStringInterner.safeIntern(element.getAttribute(DEFAULT_VALUE));
				}
				break;
			case ENUMERATED:
				if (element.getAttribute(VALUE) != null) {
					value = SafeStringInterner.safeIntern(element.getAttribute(VALUE));
				}
				if (element.getAttribute(DEFAULT_VALUE) != null) {
					defaultValue = SafeStringInterner.safeIntern(element.getAttribute(DEFAULT_VALUE));
				}

				//  Do we have enumeratedOptionValue children?  If so, load them
				//  to define the valid values and the default value.
				ICStorageElement configElements[] = element.getChildren();
				for (int i = 0; i < configElements.length; ++i) {
					ICStorageElement configNode = configElements[i];
					if (configNode.getName().equals(ENUM_VALUE)) {
						ICStorageElement configElement = configNode;
						String optId = SafeStringInterner.safeIntern(configElement.getAttribute(ID));
						if (i == 0) {
							applicableValuesList = new ArrayList<>();
							if (defaultValue == null) {
								defaultValue = optId; //  Default value to be overridden is default is specified
							}
						}
						applicableValuesList.add(optId);
						if (configElement.getAttribute(COMMAND) != null) {
							getCommandMap().put(optId,
									SafeStringInterner.safeIntern(configElement.getAttribute(COMMAND)));
						} else {
							getCommandMap().put(optId, EMPTY_STRING);
						}
						getNameMap().put(optId, SafeStringInterner.safeIntern(configElement.getAttribute(NAME)));
						if (configElement.getAttribute(IS_DEFAULT) != null) {
							Boolean isDefault = Boolean.valueOf(configElement.getAttribute(IS_DEFAULT));
							if (isDefault.booleanValue()) {
								defaultValue = optId;
							}
						}
					}
				}
				break;
			case TREE:
				if (element.getAttribute(VALUE) != null) {
					value = element.getAttribute(VALUE);
				}
				if (element.getAttribute(DEFAULT_VALUE) != null) {
					defaultValue = element.getAttribute(DEFAULT_VALUE);
				}
				break;
			case STRING_LIST:
			case INCLUDE_PATH:
			case PREPROCESSOR_SYMBOLS:
			case LIBRARIES:
			case OBJECTS:
			case INCLUDE_FILES:
			case LIBRARY_PATHS:
			case LIBRARY_FILES:
			case MACRO_FILES:
			case UNDEF_INCLUDE_PATH:
			case UNDEF_PREPROCESSOR_SYMBOLS:
			case UNDEF_INCLUDE_FILES:
			case UNDEF_LIBRARY_PATHS:
			case UNDEF_LIBRARY_FILES:
			case UNDEF_MACRO_FILES:
				//  Note:  These string-list options do not load either the "value" or
				//         "defaultValue" attributes.  Instead, the ListOptionValue children
				//         are loaded in the value field.
				List<OptionStringValue> vList = new ArrayList<>();
				List<OptionStringValue> biList = new ArrayList<>();
				configElements = element.getChildren();
				for (ICStorageElement veNode : configElements) {
					if (veNode.getName().equals(LIST_VALUE)) {
						OptionStringValue ve = new OptionStringValue(veNode);
						if (ve.isBuiltIn()) {
							biList.add(ve);
						} else {
							vList.add(ve);
						}
					}
				}

				//Assume not empty unless specificaly flagged
				boolean isValueEmpty = false;
				boolean isBuiltinEmpty = false;

				if (element.getAttribute(IS_VALUE_EMPTY) != null) {
					Boolean isEmpty = Boolean.valueOf(element.getAttribute(IS_VALUE_EMPTY));
					if (isEmpty.booleanValue()) {
						isValueEmpty = true;
					}
				}
				if (element.getAttribute(IS_BUILTIN_EMPTY) != null) {
					Boolean isEmpty = Boolean.valueOf(element.getAttribute(IS_BUILTIN_EMPTY));
					if (isEmpty.booleanValue()) {
						isBuiltinEmpty = true;
					}
				}

				if (vList.size() != 0 || isValueEmpty) {
					value = vList;
				} else {
					value = null;
				}
				if (biList.size() != 0 || isBuiltinEmpty) {
					builtIns = biList;
				} else {
					builtIns = null;
				}

				break;
			default:
				break;
			}
		} catch (BuildException e) {
			// TODO: report error
		}

		// Determine if there needs to be a browse button
		if (element.getAttribute(BROWSE_TYPE) != null) {
			String browseTypeStr = element.getAttribute(BROWSE_TYPE);

			if (browseTypeStr == null) {
				// Set to null, to indicate no browse type specification
				// This will allow any superclasses to be searched for the
				// browse type specification, and thus inherited, if found,
				// which they should be
				browseType = null;
			} else if (browseTypeStr.equals(NONE)) {
				browseType = BROWSE_NONE;
			} else if (browseTypeStr.equals(FILE)) {
				browseType = BROWSE_FILE;
			} else if (browseTypeStr.equals(DIR)) {
				browseType = BROWSE_DIR;
			}
		}

		// Get the browseFilterPath attribute
		if (element.getAttribute(BROWSE_FILTER_PATH) != null) {
			this.browseFilterPath = SafeStringInterner.safeIntern(element.getAttribute(BROWSE_FILTER_PATH));
		}

		// Get the browseFilterExtensions attribute
		if (element.getAttribute(BROWSE_FILTER_EXTENSIONS) != null) {
			String browseFilterExtensionsStr = element.getAttribute(BROWSE_FILTER_EXTENSIONS);
			if (browseFilterExtensionsStr != null) {
				this.browseFilterExtensions = SafeStringInterner
						.safeIntern(browseFilterExtensionsStr.split("\\s*,\\s*")); //$NON-NLS-1$
			}
		}

		if (element.getAttribute(CATEGORY) != null) {
			categoryId = SafeStringInterner.safeIntern(element.getAttribute(CATEGORY));
			if (categoryId != null) {
				category = holder.getOptionCategory(categoryId);
			}
		}

		// Get the resourceFilter attribute
		if (element.getAttribute(RESOURCE_FILTER) != null) {
			String resFilterStr = element.getAttribute(RESOURCE_FILTER);
			if (resFilterStr == null) {
				// Set to null, to indicate no resource filter specification
				// This will allow any superclasses to be searched for the
				// resource filter specification, and thus inherited, if found,
				// which they should be
				resourceFilter = null;
			} else if (resFilterStr.equals(ALL)) {
				resourceFilter = FILTER_ALL;
			} else if (resFilterStr.equals(FILE)) {
				resourceFilter = FILTER_FILE;
			} else if (resFilterStr.equals(PROJECT)) {
				resourceFilter = FILTER_PROJECT;
			}
		}

		// Note: valueHandlerElement and VALUE_HANDLER are not restored,
		// as they are not saved. See note in serialize().

		// valueHandlerExtraArgument
		if (element.getAttribute(VALUE_HANDLER_EXTRA_ARGUMENT) != null) {
			valueHandlerExtraArgument = SafeStringInterner
					.safeIntern(element.getAttribute(VALUE_HANDLER_EXTRA_ARGUMENT));
		}
	}

	private int ValueTypeStrToInt(String valueTypeStr) {
		if (valueTypeStr == null) {
			return -1;
		}
		if (valueTypeStr.equals(TYPE_STRING)) {
			return STRING;
		} else if (valueTypeStr.equals(TYPE_STR_LIST)) {
			return STRING_LIST;
		} else if (valueTypeStr.equals(TYPE_BOOL)) {
			return BOOLEAN;
		} else if (valueTypeStr.equals(TYPE_ENUM)) {
			return ENUMERATED;
		} else if (valueTypeStr.equals(TYPE_INC_PATH)) {
			return INCLUDE_PATH;
		} else if (valueTypeStr.equals(TYPE_LIB)) {
			return LIBRARIES;
		} else if (valueTypeStr.equals(TYPE_USER_OBJS)) {
			return OBJECTS;
		} else if (valueTypeStr.equals(TYPE_DEFINED_SYMBOLS)) {
			return PREPROCESSOR_SYMBOLS;
		} else if (valueTypeStr.equals(TYPE_LIB_PATHS)) {
			return LIBRARY_PATHS;
		} else if (valueTypeStr.equals(TYPE_LIB_FILES)) {
			return LIBRARY_FILES;
		} else if (valueTypeStr.equals(TYPE_INC_FILES)) {
			return INCLUDE_FILES;
		} else if (valueTypeStr.equals(TYPE_SYMBOL_FILES)) {
			return MACRO_FILES;
		} else if (valueTypeStr.equals(TYPE_UNDEF_INC_PATH)) {
			return UNDEF_INCLUDE_PATH;
		} else if (valueTypeStr.equals(TYPE_UNDEF_DEFINED_SYMBOLS)) {
			return UNDEF_PREPROCESSOR_SYMBOLS;
		} else if (valueTypeStr.equals(TYPE_UNDEF_LIB_PATHS)) {
			return UNDEF_LIBRARY_PATHS;
		} else if (valueTypeStr.equals(TYPE_UNDEF_LIB_FILES)) {
			return UNDEF_LIBRARY_FILES;
		} else if (valueTypeStr.equals(TYPE_UNDEF_INC_FILES)) {
			return UNDEF_INCLUDE_FILES;
		} else if (valueTypeStr.equals(TYPE_UNDEF_SYMBOL_FILES)) {
			return UNDEF_MACRO_FILES;
		} else if (valueTypeStr.equals(TYPE_TREE)) {
			return TREE;
		} else {
			ManagedBuilderCorePlugin.log(new Status(IStatus.ERROR, ManagedBuilderCorePlugin.PLUGIN_ID,
					"Invalid option type=\"" + valueTypeStr + "\" specified for option " + getId())); //$NON-NLS-1$ //$NON-NLS-2$
			// This was the CDT 2.0 default
			return PREPROCESSOR_SYMBOLS;
		}
	}

	/**
	 * Persist the option to the {@link ICStorageElement}.
	 *
	 * @param element - storage element to persist the option
	 */
	public void serialize(ICStorageElement element) throws BuildException {
		if (superClass != null) {
			element.setAttribute(IProjectType.SUPERCLASS, superClass.getId());
		} else if (superClassId != null) {
			element.setAttribute(IProjectType.SUPERCLASS, superClassId);
		}

		element.setAttribute(IBuildObject.ID, id);

		if (name != null) {
			element.setAttribute(IBuildObject.NAME, name);
		}

		if (unusedChildren != null) {
			element.setAttribute(IProjectType.UNUSED_CHILDREN, unusedChildren);
		}

		if (isAbstract != null) {
			element.setAttribute(IProjectType.IS_ABSTRACT, isAbstract.toString());
		}

		if (command != null) {
			element.setAttribute(COMMAND, command);
		}

		if (commandFalse != null) {
			element.setAttribute(COMMAND_FALSE, commandFalse);
		}

		if (isForScannerDiscovery != null) {
			element.setAttribute(USE_BY_SCANNER_DISCOVERY, isForScannerDiscovery.toString());
		}

		if (isExcludedFromScannerDiscovery != null) {
			element.setAttribute(EXCLUDE_FROM_SCANNER_DISCOVERY, isExcludedFromScannerDiscovery.toString());
		}

		if (tip != null) {
			element.setAttribute(TOOL_TIP, tip);
		}

		if (contextId != null) {
			element.setAttribute(CONTEXT_ID, contextId);
		}
		/*
		 * Note:  We store value & value-type as a pair, so we know what type of value we are
		 *        dealing with when we read it back in.
		 *        This is also true of defaultValue.
		 */
		boolean storeValueType = false;

		// value
		if (value != null) {
			storeValueType = true;
			switch (getValueType()) {
			case BOOLEAN:
				element.setAttribute(VALUE, ((Boolean) value).toString());
				break;
			case STRING:
			case ENUMERATED:
			case TREE:
				element.setAttribute(VALUE, (String) value);
				break;
			case STRING_LIST:
			case INCLUDE_PATH:
			case PREPROCESSOR_SYMBOLS:
			case LIBRARIES:
			case OBJECTS:
			case INCLUDE_FILES:
			case LIBRARY_PATHS:
			case LIBRARY_FILES:
			case MACRO_FILES:
			case UNDEF_INCLUDE_PATH:
			case UNDEF_PREPROCESSOR_SYMBOLS:
			case UNDEF_INCLUDE_FILES:
			case UNDEF_LIBRARY_PATHS:
			case UNDEF_LIBRARY_FILES:
			case UNDEF_MACRO_FILES:
				if (value != null) {
					@SuppressWarnings("unchecked")
					ArrayList<OptionStringValue> stringList = (ArrayList<OptionStringValue>) value;
					for (OptionStringValue optValue : stringList) {
						ICStorageElement valueElement = element.createChild(LIST_VALUE);
						optValue.serialize(valueElement);
					}

					if (stringList.isEmpty()) {
						element.setAttribute(IS_VALUE_EMPTY, Boolean.TRUE.toString());
					} else {
						element.setAttribute(IS_VALUE_EMPTY, Boolean.FALSE.toString());
					}
				} else {
					element.setAttribute(IS_VALUE_EMPTY, Boolean.FALSE.toString());
				}

				// Serialize the built-ins that have been overridden
				if (builtIns != null) {
					for (OptionStringValue optionValue : builtIns) {
						ICStorageElement valueElement = element.createChild(LIST_VALUE);
						optionValue.serialize(valueElement);
					}

					if (builtIns.isEmpty()) {
						element.setAttribute(IS_BUILTIN_EMPTY, Boolean.TRUE.toString());
					} else {
						element.setAttribute(IS_BUILTIN_EMPTY, Boolean.FALSE.toString());
					}
				} else {
					element.setAttribute(IS_BUILTIN_EMPTY, Boolean.FALSE.toString());
				}

				break;
			}
		}

		// defaultValue
		if (defaultValue != null) {
			storeValueType = true;
			switch (getValueType()) {
			case BOOLEAN:
				element.setAttribute(DEFAULT_VALUE, ((Boolean) defaultValue).toString());
				break;
			case STRING:
			case ENUMERATED:
			case TREE:
				element.setAttribute(DEFAULT_VALUE, (String) defaultValue);
				break;
			default:
				break;
			}
		}

		if (storeValueType) {
			String str;
			switch (getValueType()) {
			case BOOLEAN:
				str = TYPE_BOOL;
				break;
			case STRING:
				str = TYPE_STRING;
				break;
			case ENUMERATED:
				str = TYPE_ENUM;
				break;
			case STRING_LIST:
				str = TYPE_STR_LIST;
				break;
			case INCLUDE_PATH:
				str = TYPE_INC_PATH;
				break;
			case LIBRARIES:
				str = TYPE_LIB;
				break;
			case OBJECTS:
				str = TYPE_USER_OBJS;
				break;
			case PREPROCESSOR_SYMBOLS:
				str = TYPE_DEFINED_SYMBOLS;
				break;
			case INCLUDE_FILES:
				str = TYPE_INC_FILES;
				break;
			case LIBRARY_PATHS:
				str = TYPE_LIB_PATHS;
				break;
			case LIBRARY_FILES:
				str = TYPE_LIB_FILES;
				break;
			case MACRO_FILES:
				str = TYPE_SYMBOL_FILES;
				break;
			case UNDEF_INCLUDE_PATH:
				str = TYPE_UNDEF_INC_PATH;
				break;
			case UNDEF_PREPROCESSOR_SYMBOLS:
				str = TYPE_UNDEF_DEFINED_SYMBOLS;
				break;
			case UNDEF_INCLUDE_FILES:
				str = TYPE_UNDEF_INC_FILES;
				break;
			case UNDEF_LIBRARY_PATHS:
				str = TYPE_UNDEF_LIB_PATHS;
				break;
			case UNDEF_LIBRARY_FILES:
				str = TYPE_UNDEF_LIB_FILES;
				break;
			case UNDEF_MACRO_FILES:
				str = TYPE_UNDEF_SYMBOL_FILES;
				break;
			case TREE:
				str = TYPE_TREE;
				break;
			default:
				//  TODO; is this a problem...
				str = EMPTY_STRING;
				break;
			}
			element.setAttribute(VALUE_TYPE, str);
		}

		// browse type
		if (browseType != null) {
			String str;
			switch (getBrowseType()) {
			case BROWSE_NONE:
				str = NONE;
				break;
			case BROWSE_FILE:
				str = FILE;
				break;
			case BROWSE_DIR:
				str = DIR;
				break;
			default:
				str = EMPTY_STRING;
				break;
			}
			element.setAttribute(BROWSE_TYPE, str);
		}

		// browse filter path
		if (browseFilterPath != null) {
			element.setAttribute(BROWSE_FILTER_PATH, browseFilterPath);
		}

		// browse filter extensions
		if (browseFilterExtensions != null) {
			StringBuilder sb = new StringBuilder();
			for (String ext : browseFilterExtensions) {
				sb.append(ext).append(',');
			}
			element.setAttribute(BROWSE_FILTER_EXTENSIONS, sb.toString());
		}

		if (categoryId != null) {
			element.setAttribute(CATEGORY, categoryId);
		}

		// resource filter
		if (resourceFilter != null) {
			String str;
			switch (getResourceFilter()) {
			case FILTER_ALL:
				str = ALL;
				break;
			case FILTER_FILE:
				str = FILE;
				break;
			case FILTER_PROJECT:
				str = PROJECT;
				break;
			default:
				str = EMPTY_STRING;
				break;
			}
			element.setAttribute(RESOURCE_FILTER, str);
		}

		// Note: applicability calculator cannot be specified in a project file because
		//       an IConfigurationElement is needed to load it!
		if (applicabilityCalculatorElement != null) {
			//  TODO:  issue warning?
		}

		// Note: a value handler cannot be specified in a project file because
		//       an IConfigurationElement is needed to load it!
		if (valueHandlerElement != null) {
			//  TODO:  Issue warning? Stuck with behavior of this elsewhere in
			//         CDT, e.g. the implementation of Tool
		}
		if (valueHandlerExtraArgument != null) {
			element.setAttribute(VALUE_HANDLER_EXTRA_ARGUMENT, valueHandlerExtraArgument);
		}

		// I am clean now
		isDirty = false;
	}

	@Override
	public IOptionContextData getOptionContextData(IHoldsOptions holder) {
		return new OptionContextData(this, holder);
	}

	/*
	 *  P A R E N T   A N D   C H I L D   H A N D L I N G
	 */

	@Override
	public IBuildObject getParent() {
		return holder;
	}

	@Override
	public IHoldsOptions getOptionHolder() {
		// Do not take superclasses into account
		return holder;
	}

	/*
	 *  M O D E L   A T T R I B U T E   A C C E S S O R S
	 */

	@Override
	public IOption getSuperClass() {
		return superClass;
	}

	@Override
	public String getName() {
		return (name == null && superClass != null) ? superClass.getName() : name;
	}

	@Override
	public String[] getApplicableValues() {
		// Does this option instance have the list of values?
		if (applicableValuesList == null) {
			if (superClass != null) {
				return superClass.getApplicableValues();
			} else {
				return EMPTY_STRING_ARRAY;
			}
		}
		// Get all of the enumerated names from the option
		if (applicableValuesList.size() == 0) {
			return EMPTY_STRING_ARRAY;
		} else {
			// Return the elements in the order they are specified in the manifest
			String[] enumNames = new String[applicableValuesList.size()];
			for (int index = 0; index < applicableValuesList.size(); ++index) {
				enumNames[index] = getNameMap().get(applicableValuesList.get(index));
			}
			return enumNames;
		}
	}

	@Override
	public boolean getBooleanValue() {
		return ((Boolean) getValue()).booleanValue();
	}

	@Override
	public int getBrowseType() {
		if (browseType == null) {
			if (superClass != null) {
				return superClass.getBrowseType();
			} else {
				return BROWSE_NONE;
			}
		}
		return browseType.intValue();
	}

	@Override
	public String getBrowseFilterPath() {
		if (browseFilterPath == null) {
			if (superClass != null) {
				return superClass.getBrowseFilterPath();
			} else {
				return null;
			}
		}
		return browseFilterPath;
	}

	@Override
	public String[] getBrowseFilterExtensions() {
		if (browseFilterExtensions == null) {
			if (superClass != null) {
				return superClass.getBrowseFilterExtensions();
			} else {
				return null;
			}
		}
		return browseFilterExtensions.clone();
	}

	@Override
	public int getResourceFilter() {
		if (resourceFilter == null) {
			if (superClass != null) {
				return superClass.getResourceFilter();
			} else {
				return FILTER_ALL;
			}
		}
		return resourceFilter.intValue();
	}

	public IConfigurationElement getApplicabilityCalculatorElement() {
		/*		if (applicabilityCalculatorElement == null) {
					if (superClass != null) {
						return ((Option)superClass).getApplicabilityCalculatorElement();
					}
				}
		*/
		return applicabilityCalculatorElement;
	}

	@Override
	public IOptionApplicability getApplicabilityCalculator() {
		if (applicabilityCalculator == null) {
			if (applicabilityCalculatorElement != null) {
				try {
					if (applicabilityCalculatorElement.getAttribute(APPLICABILITY_CALCULATOR) != null) {
						applicabilityCalculator = (IOptionApplicability) applicabilityCalculatorElement
								.createExecutableExtension(APPLICABILITY_CALCULATOR);
					}
				} catch (CoreException e) {
					ManagedBuilderCorePlugin.log(e);
				}
			} else if (superClass != null) {
				applicabilityCalculator = superClass.getApplicabilityCalculator();
			}
		}

		return applicabilityCalculator;
	}

	@Override
	public String[] getBuiltIns() {
		// Return the list of built-ins as an array
		List<OptionStringValue> list = getExactBuiltinsList();
		List<String> valueList = listValueListToValueList(list);

		if (valueList == null) {
			return EMPTY_STRING_ARRAY;
		}
		return valueList.toArray(new String[valueList.size()]);
	}

	public List<OptionStringValue> getExactBuiltinsList() {
		// Return the list of built-ins as an array
		if (builtIns == null) {
			if (superClass != null) {
				return ((Option) superClass).getExactBuiltinsList();
			} else {
				return null;
			}
		}

		return builtIns;
	}

	@Override
	public IOptionCategory getCategory() {
		if (category == null) {
			if (superClass != null) {
				return superClass.getCategory();
			} else {
				if (getOptionHolder() instanceof ITool) {
					return ((ITool) getOptionHolder()).getTopOptionCategory();
				} else {
					return null;
				}
			}
		}
		return category;
	}

	@Override
	public IOptionDefaultValueGenerator getDefaultValueGenerator() {
		if (defaultValueGenerator == null) {
			if (defaultValueGeneratorElement != null) {
				try {
					if (defaultValueGeneratorElement.getAttribute(DEFAULTVALUE_GENERATOR) != null) {
						defaultValueGenerator = (IOptionDefaultValueGenerator) defaultValueGeneratorElement
								.createExecutableExtension(DEFAULTVALUE_GENERATOR);
					}
				} catch (CoreException e) {
					ManagedBuilderCorePlugin.log(e);
				}
			} else if (superClass != null) {
				defaultValueGenerator = superClass.getDefaultValueGenerator();
			}
		}

		return defaultValueGenerator;
	}

	@Override
	public String getCommand() {
		if (command == null) {
			if (superClass != null) {
				return superClass.getCommand();
			} else {
				return EMPTY_STRING;
			}
		}
		return command;
	}

	@Override
	public IOptionCommandGenerator getCommandGenerator() {
		if (commandGenerator == null) {
			if (commandGeneratorElement != null) {
				try {
					if (commandGeneratorElement.getAttribute(COMMAND_GENERATOR) != null) {
						commandGenerator = (IOptionCommandGenerator) commandGeneratorElement
								.createExecutableExtension(COMMAND_GENERATOR);
					}
				} catch (CoreException e) {
					ManagedBuilderCorePlugin.log(e);
				}
			} else if (superClass != null) {
				commandGenerator = superClass.getCommandGenerator();
			}
		}

		return commandGenerator;
	}

	@Override
	public String getCommandFalse() {
		if (commandFalse == null) {
			if (superClass != null) {
				return superClass.getCommandFalse();
			} else {
				return EMPTY_STRING;
			}
		}
		return commandFalse;
	}

	@Override
	public boolean isForScannerDiscovery() {
		if (isForScannerDiscovery == null) {
			isForScannerDiscovery = superClass != null && superClass.isForScannerDiscovery();
		}
		return isForScannerDiscovery;
	}

	@Override
	public boolean isExcludedFromScannerDiscovery() {
		if (isExcludedFromScannerDiscovery == null) {
			isExcludedFromScannerDiscovery = superClass != null && superClass.isExcludedFromScannerDiscovery();
		}
		return isExcludedFromScannerDiscovery;
	}

	@Override
	public String getToolTip() {
		if (tip == null) {
			if (superClass != null) {
				return superClass.getToolTip();
			} else {
				return EMPTY_STRING;
			}
		}
		return tip;
	}

	@Override
	public String getContextId() {
		if (contextId == null) {
			if (superClass != null) {
				return superClass.getContextId();
			} else {
				return EMPTY_STRING;
			}
		}
		return contextId;
	}

	@Override
	public String[] getDefinedSymbols() throws BuildException {
		if (getValueType() != PREPROCESSOR_SYMBOLS) {
			throw new BuildException(ManagedMakeMessages.getResourceString("Option.error.bad_value_type")); //$NON-NLS-1$
		}
		@SuppressWarnings("unchecked")
		ArrayList<String> v = (ArrayList<String>) getValue();
		if (v == null) {
			return EMPTY_STRING_ARRAY;
		} else {
			v.trimToSize();
			return v.toArray(new String[v.size()]);
		}
	}

	@Override
	public String getCommand(String id) throws BuildException {
		// Sanity
		if (id == null) {
			return EMPTY_STRING;
		}

		// Does this option instance have the list of values?
		if (applicableValuesList == null) {
			if (superClass != null) {
				return superClass.getCommand(id);
			} else {
				return EMPTY_STRING;
			}
		}
		if (getValueType() != ENUMERATED && getValueType() != TREE) {
			throw new BuildException(ManagedMakeMessages.getResourceString("Option.error.bad_value_type")); //$NON-NLS-1$
		}

		// First check for the command in ID->command map
		String cmd = getCommandMap().get(id);
		if (cmd == null) {
			// This may be a 1.2 project or plugin manifest. If so, the argument is the human readable
			// name of the enumeration. Search for the ID that maps to the name and use that to find the
			// command.
			for (String realID : applicableValuesList) {
				String name = getNameMap().get(realID);
				if (id.equals(name)) {
					cmd = getCommandMap().get(realID);
					break;
				}
			}
		}
		return cmd == null ? EMPTY_STRING : cmd;
	}

	@Override
	public String getEnumCommand(String id) throws BuildException {
		return getCommand(id);
	}

	@Override
	public String getEnumName(String id) throws BuildException {
		return getName(id);
	}

	@Override
	public String getName(String id) throws BuildException {
		// Sanity
		if (id == null) {
			return EMPTY_STRING;
		}

		// Does this option instance have the list of values?
		if (applicableValuesList == null) {
			if (superClass != null) {
				return superClass.getName(id);
			} else {
				return EMPTY_STRING;
			}
		}
		if (getValueType() != ENUMERATED) {
			throw new BuildException(ManagedMakeMessages.getResourceString("Option.error.bad_value_type")); //$NON-NLS-1$
		}

		// First check for the command in ID->name map
		String name = getNameMap().get(id);
		if (name == null) {
			// This may be a 1.2 project or plugin manifest. If so, the argument is the human readable
			// name of the enumeration.
			name = id;
		}
		return name;
	}

	/**
	 * A memory-safe accessor to the map of enumerated option value IDs to the commands
	 * that a tool understands.
	 *
	 * @return a Map of enumerated option value IDs to actual commands that are passed
	 * to a tool on the command line.
	 */
	private Map<String, String> getCommandMap() {
		if (commandsMap == null) {
			commandsMap = new HashMap<>();
		}
		return commandsMap;
	}

	@Override
	public String getEnumeratedId(String name) throws BuildException {
		return getId(name);
	}

	@Override
	public String getId(String name) throws BuildException {
		if (name == null) {
			return null;
		}

		// Does this option instance have the list of values?
		if (applicableValuesList == null) {
			if (superClass != null) {
				return superClass.getId(name);
			} else {
				return EMPTY_STRING;
			}
		}
		if (getValueType() != ENUMERATED && getValueType() != TREE) {
			throw new BuildException(ManagedMakeMessages.getResourceString("Option.error.bad_value_type")); //$NON-NLS-1$
		}

		Set<String> idSet = getNameMap().keySet();
		for (String id : idSet) {
			String enumName = getNameMap().get(id);
			if (name.equals(enumName)) {
				return id;
			}
		}
		return null;
	}

	/**
	 * @return a Map of enumerated option value IDs to the selection displayed to the user.
	 */
	private Map<String, String> getNameMap() {
		if (namesMap == null) {
			namesMap = new HashMap<>();
		}
		return namesMap;
	}

	@Override
	public String[] getIncludePaths() throws BuildException {
		if (getValueType() != INCLUDE_PATH) {
			throw new BuildException(ManagedMakeMessages.getResourceString("Option.error.bad_value_type")); //$NON-NLS-1$
		}
		@SuppressWarnings("unchecked")
		ArrayList<String> v = (ArrayList<String>) getValue();
		if (v == null) {
			return EMPTY_STRING_ARRAY;
		} else {
			v.trimToSize();
			return v.toArray(new String[v.size()]);
		}
	}

	@Override
	public String[] getLibraries() throws BuildException {
		if (getValueType() != LIBRARIES) {
			throw new BuildException(ManagedMakeMessages.getResourceString("Option.error.bad_value_type")); //$NON-NLS-1$
		}
		@SuppressWarnings("unchecked")
		ArrayList<String> v = (ArrayList<String>) getValue();
		if (v == null) {
			return EMPTY_STRING_ARRAY;
		} else {
			v.trimToSize();
			return v.toArray(new String[v.size()]);
		}
	}

	@Override
	public String[] getLibraryFiles() throws BuildException {
		if (getValueType() != LIBRARY_FILES) {
			throw new BuildException(ManagedMakeMessages.getResourceString("Option.error.bad_value_type")); //$NON-NLS-1$
		}
		@SuppressWarnings("unchecked")
		ArrayList<String> v = (ArrayList<String>) getValue();
		if (v == null) {
			return EMPTY_STRING_ARRAY;
		} else {
			v.trimToSize();
			return v.toArray(new String[v.size()]);
		}
	}

	@Override
	public String[] getLibraryPaths() throws BuildException {
		if (getValueType() != LIBRARY_PATHS) {
			throw new BuildException(ManagedMakeMessages.getResourceString("Option.error.bad_value_type")); //$NON-NLS-1$
		}
		@SuppressWarnings("unchecked")
		ArrayList<String> v = (ArrayList<String>) getValue();
		if (v == null) {
			return EMPTY_STRING_ARRAY;
		} else {
			v.trimToSize();
			return v.toArray(new String[v.size()]);
		}
	}

	@Override
	public String getSelectedEnum() throws BuildException {
		if (getValueType() != ENUMERATED) {
			throw new BuildException(ManagedMakeMessages.getResourceString("Option.error.bad_value_type")); //$NON-NLS-1$
		}
		return getStringValue();
	}

	@Override
	public String[] getStringListValue() throws BuildException {
		if (getValueType() != STRING_LIST) {
			throw new BuildException(ManagedMakeMessages.getResourceString("Option.error.bad_value_type")); //$NON-NLS-1$
		}
		@SuppressWarnings("unchecked")
		ArrayList<String> v = (ArrayList<String>) getValue();
		if (v == null) {
			return EMPTY_STRING_ARRAY;
		} else {
			v.trimToSize();
			return v.toArray(new String[v.size()]);
		}
	}

	@Override
	public String getStringValue() throws BuildException {
		if (getValueType() != STRING && getValueType() != ENUMERATED && getValueType() != TREE) {
			throw new BuildException(ManagedMakeMessages.getResourceString("Option.error.bad_value_type")); //$NON-NLS-1$
		}
		return getValue() == null ? EMPTY_STRING : (String) getValue();
	}

	@Override
	public String[] getUserObjects() throws BuildException {
		if (getValueType() != OBJECTS) {
			throw new BuildException(ManagedMakeMessages.getResourceString("Option.error.bad_value_type")); //$NON-NLS-1$
		}
		// This is the right puppy, so return its list value
		@SuppressWarnings("unchecked")
		ArrayList<String> v = (ArrayList<String>) getValue();
		if (v == null) {
			return EMPTY_STRING_ARRAY;
		} else {
			v.trimToSize();
			return v.toArray(new String[v.size()]);
		}
	}

	@Override
	public int getValueType() throws BuildException {
		if (valueType == null) {
			if (superClass != null) {
				return superClass.getValueType();
			} else {
				throw new BuildException(ManagedMakeMessages.getResourceString("Option.error.bad_value_type")); //$NON-NLS-1$;
			}
		}
		return valueType.intValue();
	}

	/**
	 * Gets the value, applying appropriate defaults if necessary.
	 */
	@Override
	public Object getValue() {
		/*
		 *  In order to determine the current value of an option, perform the following steps until a value is found:
		 *   1.	Examine the value attribute of the option.
		 *   2.	Examine the value attribute of the option's superClass recursively.
		 *   3.	Examine the dynamicDefaultValue attribute of the option and invoke it if specified. (not yet implemented)
		 *   4.	Examine the defaultValue attribute of the option.
		 *   5.	Examine the dynamicDefaultValue attribute of the option's superClass and invoke it if specified. (not yet implemented)
		 *   6.	Examine the defaultValue attribute of the option's superClass.
		 *   7.	Go to step 5 recursively until no more super classes.
		 *   8.	Use the default value for the option type.
		 */

		Object val = getRawValue();
		if (val == null) {
			val = getDefaultValue();
			if (val == null) {
				int valType;
				try {
					valType = getValueType();
				} catch (BuildException e) {
					return EMPTY_STRING;
				}
				switch (valType) {
				case BOOLEAN:
					val = Boolean.FALSE;
					break;
				case STRING:
				case TREE:
					val = EMPTY_STRING;
					break;
				case ENUMERATED:
					// TODO: Can we default to the first enumerated id?
					val = EMPTY_STRING;
					break;
				case STRING_LIST:
				case INCLUDE_PATH:
				case PREPROCESSOR_SYMBOLS:
				case LIBRARIES:
				case OBJECTS:
				case INCLUDE_FILES:
				case LIBRARY_PATHS:
				case LIBRARY_FILES:
				case MACRO_FILES:
				case UNDEF_INCLUDE_PATH:
				case UNDEF_PREPROCESSOR_SYMBOLS:
				case UNDEF_INCLUDE_FILES:
				case UNDEF_LIBRARY_PATHS:
				case UNDEF_LIBRARY_FILES:
				case UNDEF_MACRO_FILES:
					val = new ArrayList<String>();
					break;
				default:
					val = EMPTY_STRING;
					break;
				}
			}
		}
		return val;
	}

	public Object getExactValue() {
		/*
		 *  In order to determine the current value of an option, perform the following steps until a value is found:
		 *   1.	Examine the value attribute of the option.
		 *   2.	Examine the value attribute of the option's superClass recursively.
		 *   3.	Examine the dynamicDefaultValue attribute of the option and invoke it if specified. (not yet implemented)
		 *   4.	Examine the defaultValue attribute of the option.
		 *   5.	Examine the dynamicDefaultValue attribute of the option's superClass and invoke it if specified. (not yet implemented)
		 *   6.	Examine the defaultValue attribute of the option's superClass.
		 *   7.	Go to step 5 recursively until no more super classes.
		 *   8.	Use the default value for the option type.
		 */

		Object val = getExactRawValue();
		if (val == null) {
			val = getExactDefaultValue();
			if (val == null) {
				int valType;
				try {
					valType = getValueType();
				} catch (BuildException e) {
					return EMPTY_STRING;
				}
				switch (valType) {
				case BOOLEAN:
					val = Boolean.FALSE;
					break;
				case STRING:
				case TREE:
					val = EMPTY_STRING;
					break;
				case ENUMERATED:
					// TODO: Can we default to the first enumerated id?
					val = EMPTY_STRING;
					break;
				case STRING_LIST:
				case INCLUDE_PATH:
				case PREPROCESSOR_SYMBOLS:
				case LIBRARIES:
				case OBJECTS:
				case INCLUDE_FILES:
				case LIBRARY_PATHS:
				case LIBRARY_FILES:
				case MACRO_FILES:
				case UNDEF_INCLUDE_PATH:
				case UNDEF_PREPROCESSOR_SYMBOLS:
				case UNDEF_INCLUDE_FILES:
				case UNDEF_LIBRARY_PATHS:
				case UNDEF_LIBRARY_FILES:
				case UNDEF_MACRO_FILES:
					val = new ArrayList<OptionStringValue>();
					break;
				default:
					val = EMPTY_STRING;
					break;
				}
			}
		}
		return val;
	}

	/**
	 * Gets the raw value, applying appropriate defauls if necessary.
	 */
	public Object getRawValue() {
		Object ev = getExactRawValue();
		if (ev instanceof List<?>) {
			@SuppressWarnings("unchecked")
			List<String> evList = listValueListToValueList((List<OptionStringValue>) ev);
			return evList;
		}
		return ev;
	}

	public Object getExactRawValue() {
		if (value == null) {
			if (superClass != null) {
				Option mySuperClass = (Option) superClass;
				return mySuperClass.getExactRawValue();
			}
		}
		return value;
	}

	private List<String> listValueListToValueList(List<OptionStringValue> list) {
		if (list == null) {
			return null;
		}

		List<String> valueList = new ArrayList<>(list.size());
		for (int i = 0; i < list.size(); i++) {
			OptionStringValue el = list.get(i);
			valueList.add(el.getValue());
		}
		return valueList;
	}

	private List<OptionStringValue> valueListToListValueList(List<String> list, boolean builtIn) {
		if (list == null) {
			return null;
		}

		List<OptionStringValue> lvList = new ArrayList<>(list.size());
		for (int i = 0; i < list.size(); i++) {
			String v = list.get(i);
			lvList.add(new OptionStringValue(v, builtIn));
		}
		return lvList;
	}

	/**
	 * Gets the raw default value.
	 */
	@Override
	public Object getDefaultValue() {
		Object ev = getExactDefaultValue();
		if (ev instanceof List<?>) {
			@SuppressWarnings("unchecked")
			List<String> evList = listValueListToValueList((List<OptionStringValue>) ev);
			return evList;
		}
		return ev;
	}

	public Object getExactDefaultValue() {
		// Note: string-list options do not have a default value
		if (defaultValue == null) {
			if (superClass != null) {
				return ((Option) superClass).getExactDefaultValue();
			}
		}
		return defaultValue;
	}

	@Override
	public void setDefaultValue(Object v) {
		if (v instanceof List<?>) {
			@SuppressWarnings("unchecked")
			List<OptionStringValue> vList = valueListToListValueList((List<String>) v, false);
			defaultValue = vList;
		} else {
			defaultValue = v;
		}
		if (!isExtensionElement()) {
			setDirty(true);
			rebuildState = true;
		}
	}

	@Override
	public void setCategory(IOptionCategory category) {
		if (this.category != category) {
			this.category = category;
			if (category != null) {
				categoryId = category.getId();
			} else {
				categoryId = null;
			}
			if (!isExtensionElement()) {
				setDirty(true);
				rebuildState = true;
			}
		}
	}

	@Override
	public void setCommand(String cmd) {
		if (cmd == null && command == null) {
			return;
		}
		if (cmd == null || command == null || !cmd.equals(command)) {
			command = cmd;
			if (!isExtensionElement()) {
				isDirty = true;
				rebuildState = true;
			}
		}
	}

	@Override
	public void setCommandFalse(String cmd) {
		if (cmd == null && commandFalse == null) {
			return;
		}
		if (cmd == null || commandFalse == null || !cmd.equals(commandFalse)) {
			commandFalse = cmd;
			if (!isExtensionElement()) {
				isDirty = true;
				rebuildState = true;
			}
		}
	}

	@Override
	public void setToolTip(String tooltip) {
		if (tooltip == null && tip == null) {
			return;
		}
		if (tooltip == null || tip == null || !tooltip.equals(tip)) {
			tip = tooltip;
			if (!isExtensionElement()) {
				isDirty = true;
				rebuildState = true;
			}
		}
	}

	@Override
	public void setContextId(String id) {
		if (id == null && contextId == null) {
			return;
		}
		if (id == null || contextId == null || !id.equals(contextId)) {
			contextId = id;
			if (!isExtensionElement()) {
				isDirty = true;
				rebuildState = true;
			}
		}
	}

	@Override
	public void setResourceFilter(int filter) {
		if (resourceFilter == null || !(filter == resourceFilter.intValue())) {
			resourceFilter = Integer.valueOf(filter);
			if (!isExtensionElement()) {
				isDirty = true;
				rebuildState = true;
			}
		}
	}

	@Override
	public void setBrowseType(int type) {
		if (browseType == null || !(type == browseType.intValue())) {
			browseType = Integer.valueOf(type);
			if (!isExtensionElement()) {
				isDirty = true;
				rebuildState = true;
			}
		}
	}

	@Override
	public void setBrowseFilterPath(String path) {
		if (browseFilterPath == null || !(browseFilterPath.equals(path))) {
			browseFilterPath = path;
			if (!isExtensionElement()) {
				isDirty = true;
				rebuildState = true;
			}
		}
	}

	@Override
	public void setBrowseFilterExtensions(String[] extensions) {
		if (browseFilterExtensions == null || !(browseFilterExtensions.equals(extensions))) {
			browseFilterExtensions = extensions;
			if (!isExtensionElement()) {
				isDirty = true;
				rebuildState = true;
			}
		}
	}

	@Override
	public void setValue(boolean value) throws BuildException {
		if (/*!isExtensionElement() && */getValueType() == BOOLEAN) {
			this.value = value;
		} else {
			throw new BuildException(ManagedMakeMessages.getResourceString("Option.error.bad_value_type")); //$NON-NLS-1$
		}
		if (!isExtensionElement()) {
			setDirty(true);
			rebuildState = true;
		}
	}

	@Override
	public void setValue(String value) throws BuildException {
		// Note that we can still set the human-readable value here
		if (/*!isExtensionElement() && */(getValueType() == STRING || getValueType() == ENUMERATED
				|| getValueType() == TREE)) {
			this.value = value;
		} else {
			throw new BuildException(ManagedMakeMessages.getResourceString("Option.error.bad_value_type")); //$NON-NLS-1$
		}
		if (!isExtensionElement()) {
			setDirty(true);
			rebuildState = true;
		}
	}

	@Override
	public void setValue(String[] value) throws BuildException {
		if (/*!isExtensionElement() && */
		(getValueType() == STRING_LIST || getValueType() == INCLUDE_PATH || getValueType() == PREPROCESSOR_SYMBOLS
				|| getValueType() == LIBRARIES || getValueType() == OBJECTS || getValueType() == INCLUDE_FILES
				|| getValueType() == LIBRARY_PATHS || getValueType() == LIBRARY_FILES || getValueType() == MACRO_FILES
				|| getValueType() == UNDEF_INCLUDE_PATH || getValueType() == UNDEF_PREPROCESSOR_SYMBOLS
				|| getValueType() == UNDEF_INCLUDE_FILES || getValueType() == UNDEF_LIBRARY_PATHS
				|| getValueType() == UNDEF_LIBRARY_FILES || getValueType() == UNDEF_MACRO_FILES)) {
			// Just replace what the option reference is holding onto
			if (value == null) {
				this.value = null;
			} else {
				this.value = valueListToListValueList(Arrays.asList(value), false);
			}
		} else {
			throw new BuildException(ManagedMakeMessages.getResourceString("Option.error.bad_value_type")); //$NON-NLS-1$
		}
		if (!isExtensionElement()) {
			setDirty(true);
			rebuildState = true;
		}
	}

	public void setValue(OptionStringValue[] value) throws BuildException {
		if (/*!isExtensionElement() && */
		(getValueType() == STRING_LIST || getValueType() == INCLUDE_PATH || getValueType() == PREPROCESSOR_SYMBOLS
				|| getValueType() == LIBRARIES || getValueType() == OBJECTS || getValueType() == INCLUDE_FILES
				|| getValueType() == LIBRARY_PATHS || getValueType() == LIBRARY_FILES || getValueType() == MACRO_FILES
				|| getValueType() == UNDEF_INCLUDE_PATH || getValueType() == UNDEF_PREPROCESSOR_SYMBOLS
				|| getValueType() == UNDEF_INCLUDE_FILES || getValueType() == UNDEF_LIBRARY_PATHS
				|| getValueType() == UNDEF_LIBRARY_FILES || getValueType() == UNDEF_MACRO_FILES)) {
			// Just replace what the option reference is holding onto
			if (value == null) {
				this.value = null;
			} else {
				this.value = new ArrayList<>(Arrays.asList(value));
			}
		} else {
			throw new BuildException(ManagedMakeMessages.getResourceString("Option.error.bad_value_type")); //$NON-NLS-1$
		}
		if (!isExtensionElement()) {
			setDirty(true);
			rebuildState = true;
		}
	}

	@Override
	public void setValue(Object v) {
		if (v instanceof List<?>) {
			@SuppressWarnings("unchecked")
			List<OptionStringValue> vList = valueListToListValueList((List<String>) v, false);
			value = vList;
		} else {
			value = v;
		}
		if (!isExtensionElement()) {
			setDirty(true);
			rebuildState = true;
		}
	}

	@Override
	public void setValueType(int type) {
		// TODO:  Verify that this is a valid type
		if (valueType == null || valueType.intValue() != type) {
			valueType = Integer.valueOf(type);
			if (!isExtensionElement()) {
				setDirty(true);
				rebuildState = true;
			}
		}
	}

	public IConfigurationElement getValueHandlerElement() {
		if (valueHandlerElement == null) {
			if (superClass != null) {
				return ((Option) superClass).getValueHandlerElement();
			}
		}
		return valueHandlerElement;
	}

	public void setValueHandlerElement(IConfigurationElement element) {
		valueHandlerElement = element;
		if (!isExtensionElement()) {
			setDirty(true);
			rebuildState = true;
		}
	}

	@Override
	public IManagedOptionValueHandler getValueHandler() {
		if (valueHandler != null) {
			return valueHandler;
		}
		IConfigurationElement element = getValueHandlerElement();
		if (element != null) {
			try {
				if (element.getAttribute(VALUE_HANDLER) != null) {
					valueHandler = (IManagedOptionValueHandler) element.createExecutableExtension(VALUE_HANDLER);
					return valueHandler;
				}
			} catch (CoreException e) {
				ManagedBuildManager.optionValueHandlerError(element.getAttribute(VALUE_HANDLER), getId());
				// Assign the default handler to avoid further error messages
				valueHandler = ManagedOptionValueHandler.getManagedOptionValueHandler();
				return valueHandler;
			}
		}
		// If no handler is provided, then use the default handler
		return ManagedOptionValueHandler.getManagedOptionValueHandler();
	}

	@Override
	public String getValueHandlerExtraArgument() {
		if (valueHandlerExtraArgument == null) {
			if (superClass != null) {
				return superClass.getValueHandlerExtraArgument();
			} else {
				return EMPTY_STRING;
			}
		}
		return valueHandlerExtraArgument;
	}

	@Override
	public void setValueHandlerExtraArgument(String extraArgument) {
		if (extraArgument == null && valueHandlerExtraArgument == null) {
			return;
		}
		if (extraArgument == null || valueHandlerExtraArgument == null
				|| !extraArgument.equals(valueHandlerExtraArgument)) {
			valueHandlerExtraArgument = extraArgument;
			if (!isExtensionElement()) {
				isDirty = true;
				rebuildState = true;
			}
		}
	}

	@Override
	public String getFieldEditorId() {
		if (fieldEditorId == null) {
			if (superClass != null) {
				return ((Option) superClass).getFieldEditorId();
			}
		}
		return fieldEditorId;
	}

	@Override
	public String getFieldEditorExtraArgument() {
		if (fieldEditorExtraArgument == null) {
			if (superClass != null) {
				return superClass.getFieldEditorExtraArgument();
			} else {
				return null;
			}
		}
		return fieldEditorExtraArgument;
	}

	@Override
	public void setFieldEditorExtraArgument(String extraArgument) {
		if (extraArgument == null && fieldEditorExtraArgument == null) {
			return;
		}
		if (extraArgument == null || fieldEditorExtraArgument == null
				|| !extraArgument.equals(fieldEditorExtraArgument)) {
			fieldEditorExtraArgument = extraArgument;
			if (!isExtensionElement()) {
				isDirty = true;
				rebuildState = true;
			}
		}
	}

	/*
	 *  O B J E C T   S T A T E   M A I N T E N A N C E
	 */

	@Override
	public boolean isExtensionElement() {
		return isExtensionOption;
	}

	public boolean isDirty() {
		// This shouldn't be called for an extension option
		if (isExtensionOption) {
			return false;
		}
		return isDirty;
	}

	public void setDirty(boolean isDirty) {
		this.isDirty = isDirty;
	}

	public void resolveReferences() {
		if (!resolved) {
			resolved = true;
			// Resolve superClass
			if (superClassId != null && superClassId.length() > 0) {
				superClass = ManagedBuildManager.getExtensionOption(superClassId);
				if (superClass == null) {
					// Report error
					ManagedBuildManager.outputResolveError("superClass", //$NON-NLS-1$
							superClassId, "option", //$NON-NLS-1$
							getId());
				} else {
					//  All of our superclasses must be resolved in order to call
					//  getValueType below.
					((Option) superClass).resolveReferences();
				}
			}
			if (categoryId != null) {
				category = holder.getOptionCategory(categoryId);
				if (category == null) {
					// Report error
					ManagedBuildManager.outputResolveError("category", //$NON-NLS-1$
							categoryId, "option", //$NON-NLS-1$
							getId());
				}
			}
			// Process the value and default value attributes.  This is delayed until now
			// because we may not know the valueType until after we have resolved the superClass above
			// Now get the actual value
			try {
				IManagedConfigElement element = ManagedBuildManager.getConfigElement(this);
				switch (getValueType()) {
				case BOOLEAN:
					// Convert the string to a boolean
					String val = element.getAttribute(VALUE);
					if (val != null) {
						value = Boolean.valueOf(val);
					}
					val = element.getAttribute(DEFAULT_VALUE);
					if (val != null) {
						defaultValue = Boolean.valueOf(val);
					}
					break;
				case STRING:
					// Just get the value out of the option directly
					value = element.getAttribute(VALUE);
					defaultValue = element.getAttribute(DEFAULT_VALUE);
					break;
				case ENUMERATED:
					value = element.getAttribute(VALUE);
					defaultValue = element.getAttribute(DEFAULT_VALUE);

					//  Do we have enumeratedOptionValue children?  If so, load them
					//  to define the valid values and the default value.
					IManagedConfigElement[] enumElements = element.getChildren(ENUM_VALUE);
					for (int i = 0; i < enumElements.length; ++i) {
						String optId = SafeStringInterner.safeIntern(enumElements[i].getAttribute(ID));
						if (i == 0) {
							applicableValuesList = new ArrayList<>();
							if (defaultValue == null) {
								defaultValue = optId; //  Default value to be overridden if default is specified
							}
						}
						applicableValuesList.add(optId);
						getCommandMap().put(optId,
								SafeStringInterner.safeIntern(enumElements[i].getAttribute(COMMAND)));
						getNameMap().put(optId, SafeStringInterner.safeIntern(enumElements[i].getAttribute(NAME)));
						Boolean isDefault = Boolean.valueOf(enumElements[i].getAttribute(IS_DEFAULT));
						if (isDefault.booleanValue()) {
							defaultValue = optId;
						}
					}
					break;
				case TREE:
					value = element.getAttribute(VALUE);
					defaultValue = element.getAttribute(DEFAULT_VALUE);

					IManagedConfigElement[] treeRootConfigs = element.getChildren(TREE_ROOT);
					if (treeRootConfigs != null && treeRootConfigs.length == 1) {
						IManagedConfigElement treeRootConfig = treeRootConfigs[0];
						treeRoot = new TreeRoot(treeRootConfig, element, getParent() instanceof IToolChain);
						applicableValuesList = new ArrayList<>();
						iterateOnTree(treeRoot, new ITreeNodeIterator() {

							@Override
							public void iterateOnNode(ITreeOption node) {
							}

							@Override
							public void iterateOnLeaf(ITreeOption leafNode) {
								applicableValuesList.add(leafNode.getID());
								getCommandMap().put(leafNode.getID(), leafNode.getCommand());
								getNameMap().put(leafNode.getID(), leafNode.getName());
							}
						});
					}

					break;
				case STRING_LIST:
				case INCLUDE_PATH:
				case PREPROCESSOR_SYMBOLS:
				case LIBRARIES:
				case OBJECTS:
				case INCLUDE_FILES:
				case LIBRARY_PATHS:
				case LIBRARY_FILES:
				case MACRO_FILES:
				case UNDEF_INCLUDE_PATH:
				case UNDEF_PREPROCESSOR_SYMBOLS:
				case UNDEF_INCLUDE_FILES:
				case UNDEF_LIBRARY_PATHS:
				case UNDEF_LIBRARY_FILES:
				case UNDEF_MACRO_FILES:
					//  Note:  These string-list options do not load either the "value" or
					//         "defaultValue" attributes.  Instead, the ListOptionValue children
					//         are loaded in the value field.
					List<OptionStringValue> vList = null;
					IManagedConfigElement[] vElements = element.getChildren(LIST_VALUE);
					for (IManagedConfigElement vElement : vElements) {
						if (vList == null) {
							vList = new ArrayList<>();
							builtIns = new ArrayList<>();
						}
						OptionStringValue ve = new OptionStringValue(vElement);
						if (ve.isBuiltIn()) {
							builtIns.add(ve);
						} else {
							vList.add(ve);
						}
					}
					value = vList;
					break;
				default:
					break;
				}
			} catch (BuildException e) {
				// TODO: report error
			}
		}
	}

	/**
	 * @return Returns the managedBuildRevision.
	 */
	@Override
	public String getManagedBuildRevision() {
		if (managedBuildRevision == null) {
			if (getParent() != null) {
				return getParent().getManagedBuildRevision();
			}
		}
		return managedBuildRevision;
	}

	/* (non-Javadoc)
	 * For now implement this method just as a utility to make code
	 * within the Option class cleaner.
	 * TODO: In future we may want to move this to IOption
	 */
	protected boolean isAbstract() {
		if (isAbstract != null) {
			return isAbstract.booleanValue();
		} else {
			return false; // Note: no inheritance from superClass
		}
	}

	/**
	 * Verifies whether the option is valid and handles
	 * any errors for the option. The following errors
	 * can occur:
	 * (a) Options that are children of a ToolChain must
	 *     ALWAYS have a category
	 * (b) Options that are children of a ToolChain must
	 *     NEVER have a resourceFilter of "file".
	 * If an error occurs, the option is set to being invalid.
	 *
	 * @pre All references have been resolved.
	 */
	private void verify() {
		if (verified) {
			return;
		}
		verified = true;
		// Ignore elements that are superclasses
		if (getOptionHolder() instanceof IToolChain && isAbstract() == false) {
			// Check for error (a)
			if (getCategory() == null) {
				ManagedBuildManager.optionValidError(ManagedBuildManager.ERROR_CATEGORY, getId());
				// Object becomes invalid
				isValid = false;
			}
			// Check for error (b). Not specifying an attribute is OK.
			// Do not use getResourceFilter as it does not allow
			// differentiating between "all" and no attribute specified.
			if (resourceFilter != null) {
				switch (getResourceFilter()) {
				case IOption.FILTER_FILE:
					// TODO: Cannot differentiate between "all" and attribute not
					// specified. Thus do not produce an error. We can argue that "all"
					// means all valid resource configurations.
					ManagedBuildManager.optionValidError(ManagedBuildManager.ERROR_FILTER, getId());
					// Object becomes invalid
					isValid = false;
				}
			}
		}
	}

	@Override
	public boolean isValid() {
		// We use a lazy scheme to check whether the option is valid.
		// Note that by default an option is valid. verify() is only called if
		// the option has been resolved. This gets us around having to deal with
		// ordering problems during a resolve, or introducing another global
		// stage to verify the configuration after a resolve.
		// The trade-off is that errors in the MBS grammar may not be
		// detected on load, but only when a particular grammar element
		// is used, say in the GUI.
		if (verified == false && resolved == true) {
			verify();
		}
		return isValid;
	}

	/**
	 * @return Returns true if this Option was created from an MBS 2.0 model
	 *         OptionReference element.
	 */
	public boolean wasOptRef() {
		return wasOptRef;
	}

	public void setWasOptRef(boolean was) {
		wasOptRef = was;
	}

	/**
	 * @return Returns the version.
	 */
	@Override
	public Version getVersion() {
		if (version == null) {
			if (getParent() != null) {
				return getParent().getVersion();
			}
		}
		return version;
	}

	@Override
	public void setVersion(Version version) {
		// Do nothing
	}

	public BooleanExpressionApplicabilityCalculator getBooleanExpressionCalculator(boolean isExtensionAdjustment) {
		if (booleanExpressionCalculator == null && !isExtensionAdjustment) {
			if (superClass != null) {
				return ((Option) superClass).getBooleanExpressionCalculator(isExtensionAdjustment);
			}
		}
		return booleanExpressionCalculator;
	}

	public boolean isAdjustedExtension() {
		return isUdjusted;
	}

	public void setAdjusted(boolean adjusted) {
		isUdjusted = adjusted;
	}

	public void setSuperClass(IOption superClass) {
		if (this.superClass != superClass) {
			this.superClass = superClass;
			if (this.superClass == null) {
				superClassId = null;
			} else {
				superClassId = this.superClass.getId();
			}

			if (!isExtensionElement()) {
				setDirty(true);
			}
		}
	}

	public boolean needsRebuild() {
		return rebuildState;
	}

	public void setRebuildState(boolean rebuild) {
		if (isExtensionElement() && rebuild) {
			return;
		}

		rebuildState = rebuild;
	}

	public boolean matches(IOption option) {
		try {
			if (option.getValueType() != getValueType()) {
				return false;
			}

			if (!option.getName().equals(getName())) {
				return false;
			}
		} catch (BuildException e) {
			return false;
		}

		return true;
	}

	@Override
	public String[] getRequiredTypeIds() {
		return new String[0];
	}

	@Override
	public String[] getSupportedTypeIds() {
		String referenced[] = null;
		BooleanExpressionApplicabilityCalculator calc = getBooleanExpressionCalculator(false);

		if (calc != null) {
			referenced = calc.getReferencedPropertyIds();
		}

		if (referenced == null) {
			referenced = new String[0];
		}
		return referenced;
	}

	@Override
	public String[] getSupportedValueIds(String typeId) {
		String referenced[] = null;
		BooleanExpressionApplicabilityCalculator calc = getBooleanExpressionCalculator(false);

		if (calc != null) {
			referenced = calc.getReferencedValueIds(typeId);
		}

		if (referenced == null) {
			referenced = new String[0];
		}
		return referenced;
	}

	@Override
	public boolean requiresType(String typeId) {
		return false;
	}

	@Override
	public boolean supportsType(String id) {
		boolean supports = false;
		BooleanExpressionApplicabilityCalculator calc = getBooleanExpressionCalculator(false);

		if (calc != null) {
			if (calc.referesProperty(id)) {
				supports = true;
			}
		}
		return supports;
	}

	@Override
	public boolean supportsValue(String typeId, String valueId) {
		boolean supports = false;
		BooleanExpressionApplicabilityCalculator calc = getBooleanExpressionCalculator(false);

		if (calc != null) {
			if (calc.referesPropertyValue(typeId, valueId)) {
				supports = true;
			}
		}
		return supports;
	}

	@Override
	public String[] getBasicStringListValue() throws BuildException {
		if (getBasicValueType() != STRING_LIST) {
			throw new BuildException(ManagedMakeMessages.getResourceString("Option.error.bad_value_type")); //$NON-NLS-1$
		}
		@SuppressWarnings("unchecked")
		ArrayList<String> v = (ArrayList<String>) getValue();
		if (v == null) {
			return EMPTY_STRING_ARRAY;
		}

		return v.toArray(new String[v.size()]);
	}

	@Override
	public OptionStringValue[] getBasicStringListValueElements() throws BuildException {
		if (getBasicValueType() != STRING_LIST) {
			throw new BuildException(ManagedMakeMessages.getResourceString("Option.error.bad_value_type")); //$NON-NLS-1$
		}
		@SuppressWarnings("unchecked")
		ArrayList<OptionStringValue> v = (ArrayList<OptionStringValue>) getExactValue();
		if (v == null) {
			return EMPTY_LV_ARRAY;
		}

		return v.toArray(new OptionStringValue[v.size()]);
	}

	@Override
	public int getBasicValueType() throws BuildException {
		switch (getValueType()) {
		case IOption.BOOLEAN:
			return IOption.BOOLEAN;
		case IOption.STRING:
			return IOption.STRING;
		case IOption.ENUMERATED:
			return IOption.ENUMERATED;
		case IOption.TREE:
			return IOption.TREE;
		default:
			return IOption.STRING_LIST;
		}
	}

	public boolean hasCustomSettings() {
		if (superClass == null) {
			return true;
		}

		if (value != null && !value.equals(superClass.getValue())) {
			return true;
		}

		return false;
	}

	public static int getOppositeType(int type) {
		switch (type) {
		case INCLUDE_PATH:
			return UNDEF_INCLUDE_PATH;
		case PREPROCESSOR_SYMBOLS:
			return UNDEF_PREPROCESSOR_SYMBOLS;
		case INCLUDE_FILES:
			return UNDEF_INCLUDE_FILES;
		case LIBRARY_PATHS:
			return UNDEF_LIBRARY_PATHS;
		case LIBRARY_FILES:
			return UNDEF_LIBRARY_FILES;
		case MACRO_FILES:
			return UNDEF_MACRO_FILES;
		case UNDEF_INCLUDE_PATH:
			return INCLUDE_PATH;
		case UNDEF_PREPROCESSOR_SYMBOLS:
			return PREPROCESSOR_SYMBOLS;
		case UNDEF_INCLUDE_FILES:
			return INCLUDE_FILES;
		case UNDEF_LIBRARY_PATHS:
			return LIBRARY_PATHS;
		case UNDEF_LIBRARY_FILES:
			return LIBRARY_FILES;
		case UNDEF_MACRO_FILES:
			return MACRO_FILES;
		}
		return 0;
	}

	public static class TreeRoot extends TreeOption implements ITreeRoot {
		private boolean selectLeafOnly = true;

		TreeRoot(IManagedConfigElement element, IManagedConfigElement buildOption, boolean readTool) {
			super(element, null, readTool);
			String leaf = element.getAttribute(SELECT_LEAF_ONLY);
			if (leaf != null) {
				selectLeafOnly = Boolean.valueOf(leaf);
			}
			String toolTip = buildOption.getAttribute(TOOL_TIP);
			if (description == null && toolTip != null) {
				description = toolTip;
			}
		}

		public TreeRoot() {
			super("", "", null); //$NON-NLS-1$ //$NON-NLS-2$
		}

		public TreeRoot(TreeRoot clone) {
			super(clone, null);
			selectLeafOnly = clone.selectLeafOnly;
		}

		@Override
		public boolean isSelectLeafsOnly() {
			return selectLeafOnly;
		}

		@Override
		public ITreeOption findNode(String id) {
			if (id == null) {
				return null;
			}
			return find(id, children);
		}

		private ITreeOption find(String id, List<ITreeOption> children) {
			ITreeOption found = null;
			if (children != null) {
				for (ITreeOption child : children) {
					if (id.equals(child.getID())) {
						found = child;
						break;
					}
					found = find(id, ((TreeOption) child).children);
					if (found != null) {
						break;
					}
				}
			}
			return found;
		}

		@Override
		public ITreeOption addNode(String id, String name, String category, Integer order) {
			ITreeOption parent = this;
			if (category != null && category.length() > 0) {
				ITreeOption tempParent;
				String[] categories = category.split("\\."); //$NON-NLS-1$
				for (String cat : categories) {
					tempParent = parent.getChild(cat);
					if (tempParent == null) {
						tempParent = parent.addChild(cat, cat);
						if (order != null) {
							tempParent.setOrder(order);
						}
					}
					parent = tempParent;
				}
			}

			ITreeOption child = parent.addChild(id, name);
			if (order != null) {
				child.setOrder(order);
			}
			return child;
		}

	}

	private static class TreeOption implements ITreeOption {
		private String treeNodeId;
		private String treeNodeName;
		protected String description;
		protected String icon;
		protected String command;
		protected List<ITreeOption> children = null;
		private int order = DEFAULT_ORDER;
		private ITreeOption parent;

		TreeOption(IManagedConfigElement element, ITreeOption parent, boolean readTool) {
			treeNodeId = element.getAttribute(ID);
			treeNodeName = element.getAttribute(NAME);
			description = element.getAttribute(DESCRIPTION);
			command = element.getAttribute(COMMAND);
			icon = element.getAttribute(ICON);

			String orderStr = element.getAttribute(ORDER);
			if (orderStr != null && orderStr.trim().length() > 0) {
				try {
					order = Integer.parseInt(orderStr);
				} catch (NumberFormatException e) {
					// Do nothing, default value is used.
				}
			}
			this.parent = parent;

			IManagedConfigElement[] treeChildren = element.getChildren(TREE_VALUE);
			if (treeChildren != null && treeChildren.length > 0) {
				children = new ArrayList<>();
				for (IManagedConfigElement configElement : treeChildren) {
					children.add(new TreeOption(configElement, this, readTool));
				}
			}
		}

		TreeOption(TreeOption clone, ITreeOption parent) {
			treeNodeId = clone.treeNodeId;
			treeNodeName = clone.treeNodeName;
			description = clone.description;
			command = clone.command;
			icon = clone.icon;
			order = clone.order;
			this.parent = parent;

			if (clone.children != null) {
				children = new ArrayList<>();
				for (ITreeOption cloneChild : clone.children) {
					children.add(new TreeOption((TreeOption) cloneChild, this));
				}
			}
		}

		private TreeOption(String id, String name, ITreeOption parent) {
			this.treeNodeId = id;
			this.treeNodeName = name;
			this.parent = parent;
		}

		@Override
		public ITreeOption addChild(String id, String name) {
			ITreeOption option = new TreeOption(id, name, this);
			if (children == null) {
				children = new ArrayList<>();
			}
			children.add(0, option);
			return option;
		}

		@Override
		public boolean isContainer() {
			return children != null && !children.isEmpty(); // TODO do we need explicit marking as container for empty ones
		}

		@Override
		public String getName() {
			return treeNodeName;
		}

		@Override
		public String getID() {
			return treeNodeId;
		}

		@Override
		public String getDescription() {
			return description;
		}

		@Override
		public String getCommand() {
			return command;
		}

		@Override
		public String getIcon() {
			return icon;
		}

		@Override
		public ITreeOption[] getChildren() {
			if (children == null) {
				return null;
			}
			return children.toArray(new ITreeOption[children.size()]);
		}

		@Override
		public ITreeOption getChild(String name) {
			if (children == null || name == null) {
				return null;
			}
			for (ITreeOption child : children) {
				if (name.equals(child.getName())) {
					return child;
				}
			}
			return null;
		}

		@Override
		public ITreeOption getParent() {
			return parent;
		}

		@Override
		public int getOrder() {
			return order;
		}

		@Override
		public void setOrder(int order) {
			this.order = order;
		}

		@Override
		public void remove() {
			((TreeOption) parent).children.remove(this);

		}

		@Override
		public String toString() {
			return getName();
		}
	}

	/**
	 * Calls the iterator (visitor) on the passed parent as well as all nodes in its subtree.
	 */
	public static void iterateOnTree(ITreeOption parent, ITreeNodeIterator it) {

		it.iterateOnNode(parent);
		if (!parent.isContainer()) {
			it.iterateOnLeaf(parent);
		}

		ITreeOption[] children = parent.getChildren();
		if (children != null) {
			for (ITreeOption option : children) {
				iterateOnTree(option, it);
			}
		}
	}

	public interface ITreeNodeIterator {
		void iterateOnNode(ITreeOption node);

		void iterateOnLeaf(ITreeOption leafNode);
	}

	@Override
	public ITreeRoot getTreeRoot() throws BuildException {
		if (getValueType() != TREE) {
			throw new BuildException(ManagedMakeMessages.getResourceString("Option.error.bad_value_type")); //$NON-NLS-1$
		}
		if (treeRoot == null) {
			if (superClass != null) {
				return superClass.getTreeRoot();
			} else {
				return null;
			}
		}

		return treeRoot;
	}
}
