################################################################################
# Automatically-generated file. Do not edit!
################################################################################

-include ../makefile.init

RM := rm

# All of the sources participating in the build are defined here
-include sources.mk
-include subdir.mk

-include ../makefile.defs

OPTIONAL_TOOL_DEPS := \
$(wildcard ../makefile.defs) \
$(wildcard ../makefile.init) \
$(wildcard ../makefile.targets) \


BUILD_ARTIFACT_NAME := testMacroSupportInBuildDefinitions
BUILD_ARTIFACT_EXTENSION := tar
BUILD_ARTIFACT_PREFIX :=
BUILD_ARTIFACT := $(BUILD_ARTIFACT_PREFIX)$(BUILD_ARTIFACT_NAME)$(if $(BUILD_ARTIFACT_EXTENSION),.$(BUILD_ARTIFACT_EXTENSION),)

# Add inputs and outputs from these tool invocations to the build variables 

# All Target
all: main-build

# Main-build Target
main-build: testMacroSupportInBuildDefinitions.tar

# Tool invocations
testMacroSupportInBuildDefinitions.tar: $(BAR_FILES) makefile $(OPTIONAL_TOOL_DEPS)
	@echo 'Building target: $@'
	@echo 'Invoking: Bar Tool'
	tar cvf $@ $(BAR_FILES)
	@echo 'Finished building target: $@'
	@echo ' '

# Other Targets
clean:
	-$(RM) testMacroSupportInBuildDefinitions.tar
	-@echo ' '

.PHONY: all clean dependents main-build

-include ../makefile.targets
