/*
 * Copyright 2016 MovingBlocks
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
package org.terasology.rendering.dag;

import com.google.common.collect.Lists;
import org.terasology.engine.SimpleUri;

import java.util.List;

/**
 * TODO: Add javadocs
 */
public class RenderGraph { // TODO: add extends DirectedAcyclicGraph<Node>
    private List<Node> nodes;

    public RenderGraph() {
        nodes = Lists.newArrayList();
    }

    public String addNode(Node node, String suggestedId) { // TODO: Change suggestedId to SimpleUri
        nodes.add(node);
        node.setUri(suggestedId);
        return suggestedId; // TODO: for instance if "blur" present make id "blur1" and return it
    }

    public Node findNode(String nodeUri) {
        for (Node node: nodes) {
            if (node.getUri().equals(nodeUri)) {
                return node;
            }
        }

        return null;
    }

    // TODO: add remove, get, addEdge, removeEdge methods here

    public List<Node> getNodesInTopologicalOrder() {
        return nodes;
    }
}
