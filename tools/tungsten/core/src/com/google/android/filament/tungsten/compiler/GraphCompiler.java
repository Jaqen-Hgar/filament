/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.filament.tungsten.compiler;

import com.google.android.filament.tungsten.model.Graph;
import com.google.android.filament.tungsten.model.Node;
import com.google.android.filament.tungsten.model.Slot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * GraphCompiler takes a a graph of connected NodeModels and generates filament shader code.
 */
public final class GraphCompiler {

    private StringBuilder mMaterialFunctionBodyBuilder = new StringBuilder();
    private final List<String> mRequiredAttributes = new ArrayList<>();
    private final List<Parameter> mParameters = new ArrayList<>();
    private final Map<String, Integer> mParameterNumberMap = new HashMap<>();
    private final Map<Node.PropertyHandle, Parameter> mPropertyParameterMap = new HashMap<>();
    private final LinkedHashMap<String, String> mGlobalFunctions = new LinkedHashMap<>();
    private final Map<String, Integer> mVariableNameMap = new HashMap<>();

    private final @NotNull Graph mGraph;
    private final @NotNull Node mRootNode;
    private final Map<Slot, Expression> mCompiledVariableMap = new HashMap<>();

    public GraphCompiler(@NotNull Graph graph) {
        mGraph = graph;
        mRootNode = Objects.requireNonNull(mGraph.getRootNode());
    }

    @NotNull
    public CompiledGraph compileGraph() {
        mRootNode.getCompileFunction().invoke(mRootNode, this);
        String fragmentSection = GraphFormatter.formatFragmentSection(mGlobalFunctions.values(),
                mMaterialFunctionBodyBuilder.toString());
        String materialDefinition =
                GraphFormatter.formatMaterialSection(mRequiredAttributes, mParameters) +
                        fragmentSection;
        return new CompiledGraph(materialDefinition, mPropertyParameterMap);
    }

    /**
     * Compile the Node connected to InputSlot and retrieve its Expression entry.
     * @return null, if the InputSlot is not connected to any OutputSlot, otherwise an Expression.
     */
    @Nullable
    public Expression compileAndRetrieveExpression(@NotNull Node.InputSlot slot) {
        Node.OutputSlot outputSlot = mGraph.getOutputSlotConnectedToInput(slot);
        if (outputSlot == null) {
            return null;
        }

        // Check if we've already compiled
        Expression compiledExpression = mCompiledVariableMap.get(outputSlot);
        if (compiledExpression != null) {
            return compiledExpression;
        }

        // Compile the connected node
        Node connectedNode = mGraph.getNodeForOutputSlot(outputSlot);
        if (connectedNode == null) {
            throw new RuntimeException("Output slot references node that does not exist in graph.");
        }
        connectedNode.getCompileFunction().invoke(connectedNode, this);

        // Verify that the connected node has set it's output variable
        compiledExpression = mCompiledVariableMap.get(outputSlot);
        if (compiledExpression == null) {
            throw new RuntimeException(
                    "Output node did not set an output Expression on output slot: " + outputSlot);
        }

        return compiledExpression;
    }

    public void setExpressionForSlot(@NotNull Slot slot, @NotNull Expression expression) {
        mCompiledVariableMap.put(slot, expression);
    }

    /**
     * Called by a NodeModel subclass to denote that the given vertex attribute is required.
     * The attribute will be added to the material source's "requires" section.
     *
     * @param requirement The name of the vertex attribute, such as "uv0"
     */
    public void requireAttribute(String requirement) {
        if (!mRequiredAttributes.contains(requirement)) {
            mRequiredAttributes.add(requirement);
        }
    }

    /**
     * Called by a node's compile function to add a new material parameter.
     * The parameter will be added to the material source's "parameters" section.
     *
     * @param type The type of parameter
     * @return A String with the unique name of the parameter that the node should use in code.
     */
    @NotNull
    public Parameter addParameter(@NotNull String type, @NotNull String name) {
        String parameterName = allocateNewParameterName(name);
        Parameter parameter = new Parameter(type, parameterName);
        mParameters.add(parameter);
        return parameter;
    }

    /**
    * Associates a material parameter with a Node's property. After the graph is compiled, this
    * mapping between parameters and properties is returned so that adjustments to a property
    * can affect the appropriate material parameter.
    */
    public void associateParameterWithProperty(@NotNull Parameter parameter,
            @NotNull Node.PropertyHandle property) {
        mPropertyParameterMap.put(property, parameter);
    }

    /**
     * Called by a NodeModel subclass to allocate a new temporary variable for its use.
     * @return The symbol name of the variable.
     */
    public String getNewTemporaryVariableName(String name) {
        int iterationNumber = mVariableNameMap.getOrDefault(name, 0);
        String result = name + iterationNumber;
        mVariableNameMap.put(name, ++iterationNumber);
        return result;
    }

    /**
     * Called by a NodeModel subclass to add code to the material function's body.
     * @param code code to be concatenated to the material function body.
     */
    public void addCodeToMaterialFunctionBody(String code) {
        mMaterialFunctionBodyBuilder.append(code);
    }

    /**
     * Convenience method for calling addCodeToMaterialFunctionBody with a format String and
     * arguments.
     */
    public void addCodeToMaterialFunctionBody(String format, Object... args) {
        addCodeToMaterialFunctionBody(String.format(format, args));
    }

    /**
     * Called by a NodeModel to add a global function to the material code.
     * @param requestedName The name the Node wishes to use for the function.
     * @return The resulting symbol name for the global function. The NodeModel should use this name
     * when supplying the function definition and for references to the function in shader code.
     */
    public String allocateGlobalFunction(String requestedName, String scope) {
        // Mangle the function name
        String mangledName = requestedName + "_" + scope;
        if (!mGlobalFunctions.containsKey(mangledName)) {
            mGlobalFunctions.put(mangledName, "");
        }
        return mangledName;
    }

    /**
     * Called by a NodeModule to provide a definition for a previously allocated global function.
     * @param symbolName The name of the function symbol returned by allocateGlobalFunction.
     * @param definition The complete source text of the function, using symbolName.
     */
    public void provideFunctionDefinition(String symbolName, String definition) {
        mGlobalFunctions.put(symbolName, definition);
    }

    /**
     * Convenience method for providing a function definition that uses a format String and
     * arguments.
     */
    public void provideFunctionDefinition(String symbolName, String format, Object... args) {
        provideFunctionDefinition(symbolName, String.format(format, args));
    }

    /**
     * Appends an integer to a parameter name to ensure globally-unique names.
     */
    private String allocateNewParameterName(String name) {
        Integer parameterNumber =
                mParameterNumberMap.computeIfPresent(name, (s, integer) -> integer + 1);
        if (parameterNumber == null) {
            parameterNumber = 0;
            mParameterNumberMap.putIfAbsent(name, parameterNumber);
        }
        return name + parameterNumber;
    }
}
