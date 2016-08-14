package nl.matsv.paaaas.module.modules.metadata;

import nl.matsv.paaaas.data.VersionDataFile;
import nl.matsv.paaaas.data.VersionMeta;
import nl.matsv.paaaas.data.metadata.MetadataEntry;
import nl.matsv.paaaas.data.metadata.MetadataTree;
import nl.matsv.paaaas.module.Module;
import nl.matsv.paaaas.storage.StorageManager;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class MetadataModule extends Module {
    @Autowired
    private StorageManager storageManager;
    private Map<String, ClassNode> classes = new HashMap<>();
    private String entity;
    private String dataWatcher;
    private String entityTypes;

    @Override
    public void run(VersionDataFile versionDataFile) {
        if (versionDataFile.getVersion().getReleaseTime().getTime() < 1292976000000L){
            VersionMeta meta = versionDataFile.getMetadata();

            meta.setEnabled(false);
            meta.addError("This version is too old to Metadata.");

            System.out.println("Skip " + versionDataFile.getVersion().getId() + " for metadata because it's too old");
            return;
        }
        File file = new File(storageManager.getJarDirectory(), versionDataFile.getVersion().getId() + ".jar");

        // Generate Metadata
        JarFile jarFile;
        try {
            jarFile = new JarFile(file);
        } catch (IOException e) {
            System.out.println("Missing jar file " + file.getAbsolutePath());
            return;
        }

        Enumeration<JarEntry> iter = jarFile.entries();
        while (iter.hasMoreElements()) {
            JarEntry entry = iter.nextElement();
            if (entry.getName().endsWith(".class") && (entry.getName().startsWith("net/minecraft") || !entry.getName().contains("/"))) {
                ClassReader reader;
                try {
                    reader = new ClassReader(jarFile.getInputStream(entry));
                } catch (IOException e) {
                    e.printStackTrace();
                    return;
                }

                ClassNode node = new ClassNode();
                reader.accept(node, ClassReader.EXPAND_FRAMES);
                classes.put(entry.getName().replace('/', '.').replace(".class", ""), node);
            }
        }

        // Using magic technology find classes :D
        entity = findClassFromConstant("entityBaseTick");
        entityTypes = findClassFromConstant("Skipping Entity with id {}");
        if(entityTypes == null){
            // 1.9.4 & below
            entityTypes = findClassFromConstant("Skipping Entity with id ");
        }
        if(entity == null) {
            // b1.8.1 & below
            entity = findClassFromConstant("FallDistance"); // woo nbt
        }
        dataWatcher = findClassFromConstant("Data value id is too big with ");
        if (entity == null || entityTypes == null || dataWatcher == null) {
            VersionMeta meta = versionDataFile.getMetadata();

            meta.setEnabled(false);
            meta.addError("Metadata: Could not find constants, found " +
                    "Entity: " + entity + " Types: " + entityTypes + " Data Watcher: " + dataWatcher);

            System.out.println("Constants have changed!! " + versionDataFile.getVersion().getId());
            return;
        }

        List<String> queue = new ArrayList<>(classes.keySet());
        ClassTree object = new ClassTree("java.lang.Object");
        String current = null;
        while (queue.size() > 0) {
            if (object.contains(current) || current == null) {
                current = queue.get(0);
            }
            ClassNode clazz = classes.get(current);
            if (clazz != null) {
                // Check super
                String superC = clazz.superName.replace('/', '.');
                if (object.getName().equals(superC) || object.contains(superC)) {
                    object.insert(superC, current);
                    queue.remove(current);
                    current = null;
                } else {
                    if (queue.contains(superC)) {
                        current = superC;
                    } else {
                        queue.remove(current);
                        current = null;
                    }
                }
            } else {
                queue.remove(current);
                current = null;
            }
        }
        ClassTree tree = object.find(entity);
        MetadataTree output = metadataTree(tree, 0);
        versionDataFile.setMetadataTree(output);
    }

    private Optional<String> resolveName(String clazz) {
        if (clazz.equals(entity)) Optional.of("Entity");

        ClassNode entityTypesNode = classes.get(entityTypes);
        InvokeClassStringExtractor extractor = new InvokeClassStringExtractor(clazz, entityTypes);
        entityTypesNode.accept(extractor);
        if (extractor.getFoundName() == null)
            return Optional.empty();
        return Optional.of(extractor.getFoundName());
    }

    private MetadataTree metadataTree(ClassTree tree, int i) {
        MetadataTree output = new MetadataTree();

        List<MetadataEntry> mt = metadata(tree.getName());
        output.setClassName(tree.getName());
        output.setEntityName(resolveName(tree.getName()));

        for (MetadataEntry meta : mt) {
            meta.setIndex(i++);
        }
        output.getMetadata().addAll(mt);
        for (ClassTree item : tree.getChildren()) {
            output.getChildren().add(metadataTree(item, i));
        }
        return output;
    }
    private List<MetadataEntry> metadata(String name) {
        List<MetadataEntry> results = new ArrayList<>();
        ClassNode node = classes.get(name);
        List<MethodNode> methods = node.methods;
        for (MethodNode method : methods) {
            if (method.name.equals("<clinit>")) {
                // Static init
                MethodInsnNode lastMethod = null;
                for (AbstractInsnNode insn : method.instructions.toArray()) {
                    if (insn instanceof MethodInsnNode) {
                        if (((MethodInsnNode) insn).owner.equals(dataWatcher)) {
                            lastMethod = (MethodInsnNode) insn;
                        }
                    }
                    if (insn instanceof FieldInsnNode) {
                        FieldInsnNode fieldInsn = (FieldInsnNode) insn;
                        if (insn.getOpcode() == Opcodes.PUTSTATIC) {
                            if (lastMethod != null) {
                                if (!fieldInsn.owner.equals(name)) {
                                    continue;
                                }
                                // Find field
                                for (FieldNode fieldNode : (List<FieldNode>) node.fields) {
                                    if (fieldNode.name.equals(fieldInsn.name)) {
                                        // Got signature
                                        results.add(new MetadataEntry(0, fieldNode.name, lastMethod.name, fieldNode.signature));
                                        break;
                                    }
                                }
                            }
                        }
                        lastMethod = null;
                    }
                }
            }
        }
        return results;
    }

    private String findClassFromConstant(String str) {
        for (Map.Entry<String, ClassNode> s : classes.entrySet()) {
            ClassNode clazz = s.getValue();
            List<MethodNode> methods = clazz.methods;
            for (MethodNode method : methods) {
                for (AbstractInsnNode insnNode : method.instructions.toArray()) {
                    if (insnNode instanceof LdcInsnNode) {
                        LdcInsnNode ldc = (LdcInsnNode) insnNode;
                        if (str.equals(ldc.cst)) {
                            return s.getKey();
                        }
                    }
                }
            }
        }
        return null;
    }
}