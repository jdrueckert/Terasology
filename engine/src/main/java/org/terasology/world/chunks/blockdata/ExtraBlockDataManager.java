/*
 * Copyright 2018 MovingBlocks
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

package org.terasology.world.chunks.blockdata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.context.Context;
import org.terasology.engine.module.ModuleManager;
import org.terasology.module.ModuleEnvironment;
import org.terasology.module.sandbox.API;
import org.terasology.world.block.Block;
import org.terasology.world.block.BlockManager;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Keeps track of the extra per-block storage fields which may be registered by mods for
 * their own use. If multiple fields of the same size are registered for disjoint sets
 * of blocks, they may be made aliases of the same field, to save space.
 *
 * To register extra data fields, annotate a class with @ExtraDataSystem, and include a
 * public static method annotated with @RegisterExtraData which determines, for each block,
 * whether the field is applicable for that block. For example:
 *
 * {@code
 * @ExtraDataSystem
 * public class ExampleExtraDataSystem {
 *     @RegisterExtraData(name="exampleModule.grassNutrients", bitSize=8)
 *     public static boolean shouldHaveNutrients(Block block) {
 *         return block.isGrass();
 *     }
 *  }
 *  }
 */
@API
public class ExtraBlockDataManager {
    private static final Logger logger = LoggerFactory.getLogger(ExtraBlockDataManager.class);
    private static final Map<Integer,TeraArray.Factory<? extends TeraArray>> teraArrayFactories = new HashMap<>();
    static {
        teraArrayFactories.put(4, new TeraSparseArray4Bit.Factory());
        teraArrayFactories.put(8, new TeraSparseArray8Bit.Factory());
        teraArrayFactories.put(16,new TeraSparseArray16Bit.Factory());
    }
    
    private Map<String,Integer> slots;
    private TeraArray.Factory<? extends TeraArray>[] slotFactories;
    
    // For testing purposes: don't add any fields.
    public ExtraBlockDataManager() {
        slots = new HashMap<>();
        slotFactories = new TeraArray.Factory[0];
    }
    
    public ExtraBlockDataManager(Context context) {
        ModuleEnvironment environment = context.get(ModuleManager.class).getEnvironment();
        Collection<Block> blocks = context.get(BlockManager.class).listRegisteredBlocks();
        
        HashMap<Integer, HashMap<String, HashSet<Block>>> fieldss = new HashMap<>();
        teraArrayFactories.forEach((size, fac) -> fieldss.put(size, new HashMap<>()));
        
        for (Class<?> type : environment.getTypesAnnotatedWith(ExtraDataSystem.class)) {
            for (Method method : type.getMethods()) {
                RegisterExtraData registerAnnotation = method.getAnnotation(RegisterExtraData.class);
                if (registerAnnotation != null) {
                    String errorType = validRegistrationMethod(method, registerAnnotation);
                    if (errorType != null) {
                        logger.error("Unable to register extra block data: "+errorType+" for {}.{}: should be \"public static boolean {}(Block block)\", and bitSize should be 4, 8 or 16.", type.getName(), method.getName(), method.getName());
                        continue;
                    }
                    method.setAccessible(true);
                    HashSet<Block> includedBlocks = new HashSet<>();
                    for (Block block : blocks) {
                        try {
                            if ((boolean) method.invoke(null, block)) {
                                includedBlocks.add(block);
                            }
                        } catch(IllegalAccessException e) {
                            // This should not get to this point.
                            throw new RuntimeException("Incorrect access modifier on register extra data method", e);
                        } catch(InvocationTargetException e) {
                            throw new RuntimeException(e);
                        }
                    }
                    fieldss.get(registerAnnotation.bitSize()).put(registerAnnotation.name(), includedBlocks);
                }
            }
        }
        
        slots = new HashMap<>();
        ArrayList<TeraArray.Factory<?>> tempSlotTypes = new ArrayList<>();
        fieldss.forEach((size, fields) -> {
            Graph disjointnessGraph = getDisjointnessGraph(fields);
            ArrayList<ArrayList<String>> cliques = findCliqueCover(disjointnessGraph);
            for (ArrayList<String> clique : cliques) {
                for (String label : clique) {
                    slots.put(label, tempSlotTypes.size());
                }
                tempSlotTypes.add(teraArrayFactories.get(size));
            }
        });
        slotFactories = tempSlotTypes.toArray(new TeraArray.Factory<?>[0]);
        String loggingOutput = "Extra data slots registered:";
        boolean first = true;
        for (Map.Entry<String, Integer> entry : slots.entrySet()) {
            loggingOutput += (first ? " " : ", ") + entry.getKey() + " -> " + entry.getValue();
            first = false;
        }
        logger.info(loggingOutput);
    }
    
    private static String validRegistrationMethod(Method method, RegisterExtraData annotation) {
        Class<?>[] argumentTypes = method.getParameterTypes();
        return method.getReturnType() != boolean.class ? "incorrect return type" :
               //! method.isAccessible() ? "method not accessible" :
               ! teraArrayFactories.containsKey(annotation.bitSize()) ? "invalid bitSize" :
               ! Modifier.isStatic(method.getModifiers()) ? "method not static" :
               argumentTypes.length != 1 ? "arguments list has wrong length" :
               argumentTypes[0] != Block.class ? "incorrect argument type" :
               null;
    }
    
    private static class Graph {
        public String[] verts;
        public HashMap<String, Set<String>> edges;
        
        public Graph(String[] verts, HashMap<String, Set<String>> edges) {
            this.verts = verts;
            this.edges = edges;
        }
        
        public Graph(String[] verts) {
            this.verts = verts;
            this.edges = new HashMap<>();
            for (String vert : verts) {
                edges.put(vert, new HashSet());
            }
        }
        
        public Graph addEdge(String s0, String s1) {
            edges.get(s0).add(s1);
            edges.get(s1).add(s0);
            return this;
        }
        
        public Graph removeEdge(String s0, String s1) {
            edges.get(s0).remove(s1);
            edges.get(s1).remove(s0);
            return this;
        }
        
        // Creates a new graph containing the complement of the contraction of the complement.
        public Graph ntract(String s0, String s1) {
            int v0, v1 = -1;
            for (int i=0; i<verts.length; i++) {
                if (verts[i].equals(s0)) {
                    v0 = i;
                }
                if (verts[i].equals(s1)) {
                    v1 = i;
                }
            }
            String[] newVerts = new String[verts.length-1];
            System.arraycopy(verts, 0, newVerts, 0, v1);
            System.arraycopy(verts, v1+1, newVerts, v1, verts.length-v1-1);
            HashMap<String, Set<String>> newEdges = new HashMap<>();
            for (String s : verts) {
                newEdges.put(s, new HashSet<>(edges.get(s)));
            }
            newEdges.remove(s1);
            Set<String> e0 = newEdges.get(s0);
            Set<String> e1 = edges.get(s1);
            for (String s2 : verts) {
                if (e0.contains(s2) && !e1.contains(s2)) {
                    e0.remove(s2);
                    newEdges.get(s2).remove(s0);
                }
                if (e1.contains(s2)) {
                    newEdges.get(s2).remove(s1);
                }
            }
            return new Graph(newVerts, newEdges);
        }
        
        public String toString() {
            String result = "Graph:";
            for (int i=0; i<verts.length; i++) {
                result += " (" + verts[i] + " ->";
                for (String v : edges.get(verts[i])) {
                    result += " " + v;
                }
                result += ")";
            }
            return result;
        }
    }
    
    private static Graph getDisjointnessGraph(Map<String, HashSet<Block>> fields) {
        Graph graph = new Graph(fields.keySet().toArray(new String[0]));
        fields.forEach((name0, blockSet0) ->
            fields.forEach((name1, blockSet1) -> {
                if (name0.compareTo(name1) < 0 && isDisjoint(blockSet0, blockSet1)) {
                    graph.addEdge(name0, name1);
                }
            })
        );
        return graph;
    }
    
    private static <T> boolean isDisjoint(Set<T> s0, Set<T> s1) {
        for (T t : s0) {
            if (s1.contains(t)) {
                return false;
            }
        }
        return true;
    }
    
    //This is exponential time, but the problem is known to be NP-hard in general and large cases are unlikely to come up.
    private static ArrayList<ArrayList<String>> findCliqueCover(Graph graph) {
        return findCliqueCover(graph, Integer.MAX_VALUE, "");
    }
    
    private static ArrayList<ArrayList<String>> findCliqueCover(Graph graph, int bestSize, String tabs) {
        verboseLog(tabs+"findCliqueCover up to "+bestSize+", "+graph.toString());
        for (int i=0; i<graph.verts.length; i++) {
            if (i >= bestSize-1) {
                verboseLog(tabs+"giving up");
                return null;
            }
            String v0 = graph.verts[i];
            if (!graph.edges.get(v0).isEmpty()) {
                verboseLog(tabs+"Selected vertex "+v0);
                String v1 = graph.edges.get(v0).iterator().next();
                ArrayList<ArrayList<String>> bestCover0 = findCliqueCover(graph.ntract(v0,v1), bestSize, tabs+"----");
                int bestSize0 = bestCover0 == null ? bestSize : bestCover0.size();
                graph.removeEdge(v0,v1);
                ArrayList<ArrayList<String>> bestCover1 = findCliqueCover(graph, bestSize0, tabs+"    ");
                graph.addEdge(v0,v1);
                if (bestCover1 != null) {
                    return bestCover1;
                } else {
                    if (bestCover0 != null) {
                        bestCover0.get(i).add(v1);
                    }
                    return bestCover0;
                }
            }
        }
        verboseLog(tabs+"done, "+graph.verts.length);
        ArrayList<ArrayList<String>> bestCover = new ArrayList<>();
        for (int i=0; i<graph.verts.length; i++) {
            ArrayList<String> singleton = new ArrayList<>();
            singleton.add(graph.verts[i]);
            bestCover.add(singleton);
        }
        return bestCover;
    }
    
    // Log something, but only when this class is being tested.
    private static void verboseLog(String string) {
        //logger.info(string);
    }
    
    /**
     * Get the numerical index associated with the extra data field name.
     * This numerical index is needed for most extra-data access methods.
     */
    public int getSlotNumber(String name) {
        Integer index = slots.get(name);
        if (index == null) {
            throw new IllegalArgumentException("Extra-data name not registered: " + name);
        }
        return index;
    }
    
    public TeraArray[] makeDataArrays(int sizeX, int sizeY, int sizeZ) {
        TeraArray[] extraData = new TeraArray[slotFactories.length];
        for (int i=0; i<extraData.length; i++) {
            extraData[i] = slotFactories[i].create(sizeX, sizeY, sizeZ);
        }
        return extraData;
    }
}
