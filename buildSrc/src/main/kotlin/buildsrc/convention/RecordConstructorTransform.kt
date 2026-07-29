package buildsrc.convention

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassWriter
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.Type
import org.jetbrains.org.objectweb.asm.tree.AbstractInsnNode
import org.jetbrains.org.objectweb.asm.tree.ClassNode
import org.jetbrains.org.objectweb.asm.tree.FieldInsnNode
import org.jetbrains.org.objectweb.asm.tree.FieldNode
import org.jetbrains.org.objectweb.asm.tree.InsnList
import org.jetbrains.org.objectweb.asm.tree.MethodInsnNode
import org.jetbrains.org.objectweb.asm.tree.MethodNode
import org.jetbrains.org.objectweb.asm.tree.VarInsnNode
import java.nio.file.Files
import java.nio.file.Path

private const val DEFENSIVE_SNAPSHOT = "Ldev/vibris/api/DefensiveSnapshot;"
private const val NORMALIZE_PATH = "Ldev/vibris/core/NormalizePathAtRecordBoundary;"
private const val LIST = "Ljava/util/List;"
private const val MAP = "Ljava/util/Map;"
private const val PATH = "Ljava/nio/file/Path;"

/**
 * Rewrites marked Kotlin record inputs before their canonical constructors store component fields.
 * Kotlin deliberately does not expose Java's compact-record-constructor assignment semantics.
 */
fun Project.transformMarkedRecordConstructors() {
    tasks.named("compileKotlin", KotlinJvmCompile::class.java) {
        inputs.property("markedRecordConstructorTransform", 1)
        doLast("transformMarkedRecordConstructors") {
            val root = destinationDirectory.get().asFile.toPath()
            var transformed = 0
            Files.walk(root).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".class") }
                    .forEach { path ->
                        if (transformRecordClass(path)) {
                            transformed++
                        }
                    }
            }
            logger.info("Transformed {} marked record constructor(s).", transformed)
        }
    }
}

private fun transformRecordClass(path: Path): Boolean {
    val original = Files.readAllBytes(path)
    val reader = ClassReader(original)
    val node = ClassNode()
    reader.accept(node, 0)
    val transforms = node.fields.mapNotNull(FieldNode::markedTransform).associateBy(Transform::fieldName)
    if (transforms.isEmpty()) {
        return false
    }
    if (node.access and Opcodes.ACC_RECORD == 0) {
        throw GradleException("Marked class ${node.name} is not a record")
    }

    val components = node.recordComponents ?: throw GradleException("Marked class ${node.name} is not a record")
    val componentTypes = components.map { Type.getType(it.descriptor) }
    val constructorDescriptor = Type.getMethodDescriptor(Type.VOID_TYPE, *componentTypes.toTypedArray())
    val constructor = node.methods.singleOrNull { it.name == "<init>" && it.desc == constructorDescriptor }
        ?: throw GradleException("Canonical constructor not found for marked record ${node.name}")
    val plannedTransforms = mutableListOf<PlannedTransform>()
    val matchedFields = mutableSetOf<String>()
    var localSlot = 1
    for ((component, type) in components.zip(componentTypes)) {
        val transform = transforms[component.name]
        if (transform != null) {
            if (constructor.componentStoreCount(node.name, component.name, component.descriptor) != 1) {
                throw GradleException(
                    "Canonical constructor must assign marked field ${node.name}.${component.name} exactly once",
                )
            }
            matchedFields.add(component.name)
            plannedTransforms.add(PlannedTransform(transform, localSlot))
        }
        localSlot += type.size
    }
    if (matchedFields != transforms.keys) {
        throw GradleException(
            "Marked fields are not record components in ${node.name}: ${transforms.keys - matchedFields}",
        )
    }
    val insertionPoint = constructor.markedAssignmentStart(node.name, plannedTransforms)
    if (constructor.hasExactPrologue(insertionPoint, plannedTransforms)) {
        return false
    }

    val injected = InsnList()
    plannedTransforms.forEach { injected.add(it.transform.instructions(it.localSlot)) }
    constructor.instructions.insertBefore(insertionPoint, injected)
    val writer = ClassWriter(reader, ClassWriter.COMPUTE_MAXS)
    node.accept(writer)
    Files.write(path, writer.toByteArray())
    return true
}

private fun FieldNode.markedTransform(): Transform? {
    val markers = (visibleAnnotations.orEmpty().asSequence() + invisibleAnnotations.orEmpty().asSequence())
        .map { it.desc }
        .filter { it == DEFENSIVE_SNAPSHOT || it == NORMALIZE_PATH }
        .toList()
    if (markers.size > 1) {
        throw GradleException("Field $name has conflicting record constructor markers: $markers")
    }
    return when (markers.singleOrNull()) {
        DEFENSIVE_SNAPSHOT -> when (desc) {
            LIST -> Transform(fieldName = name, owner = "java/util/List", method = "copyOf",
                descriptor = "(Ljava/util/Collection;)Ljava/util/List;")
            MAP -> Transform(fieldName = name, owner = "java/util/Map", method = "copyOf",
                descriptor = "(Ljava/util/Map;)Ljava/util/Map;")
            else -> throw GradleException("@$DEFENSIVE_SNAPSHOT does not support field $name:$desc")
        }
        NORMALIZE_PATH -> {
            if (desc != PATH) {
                throw GradleException("@$NORMALIZE_PATH requires a Path field, got $name:$desc")
            }
            Transform(
                fieldName = name,
                owner = "java/nio/file/Path",
                method = "normalize",
                descriptor = "()Ljava/nio/file/Path;",
            )
        }
        else -> null
    }
}

private data class PlannedTransform(val transform: Transform, val localSlot: Int)

private data class Transform(
    val fieldName: String,
    val owner: String,
    val method: String,
    val descriptor: String,
) {
    fun instructions(localSlot: Int): InsnList = InsnList().apply {
        add(VarInsnNode(Opcodes.ALOAD, localSlot))
        if (owner == "java/nio/file/Path") {
            add(MethodInsnNode(Opcodes.INVOKEINTERFACE, owner, "toAbsolutePath", descriptor, true))
            add(MethodInsnNode(Opcodes.INVOKEINTERFACE, owner, method, descriptor, true))
        } else {
            add(MethodInsnNode(Opcodes.INVOKESTATIC, owner, method, descriptor, true))
        }
        add(VarInsnNode(Opcodes.ASTORE, localSlot))
    }

    val instructionCount: Int
        get() = if (owner == "java/nio/file/Path") 4 else 3

    fun matches(instructions: List<AbstractInsnNode>, offset: Int, localSlot: Int): Boolean {
        val load = instructions.getOrNull(offset) as? VarInsnNode
        val firstCall = instructions.getOrNull(offset + 1) as? MethodInsnNode
        val call = if (owner == "java/nio/file/Path") {
            val secondCall = instructions.getOrNull(offset + 2) as? MethodInsnNode
            if (firstCall.matches(Opcodes.INVOKEINTERFACE, owner, "toAbsolutePath", descriptor, true)) {
                secondCall
            } else {
                null
            }
        } else {
            firstCall
        }
        val storeOffset = offset + instructionCount - 1
        val store = instructions.getOrNull(storeOffset) as? VarInsnNode
        val opcode = if (owner == "java/nio/file/Path") Opcodes.INVOKEINTERFACE else Opcodes.INVOKESTATIC
        return load?.opcode == Opcodes.ALOAD && load.`var` == localSlot &&
            call.matches(opcode, owner, method, descriptor, true) &&
            store?.opcode == Opcodes.ASTORE && store.`var` == localSlot
    }
}

private fun MethodInsnNode?.matches(
    opcode: Int,
    owner: String,
    name: String,
    descriptor: String,
    isInterface: Boolean,
): Boolean = this?.opcode == opcode && this.owner == owner && this.name == name &&
    desc == descriptor && itf == isInterface

private fun MethodNode.componentStoreCount(owner: String, fieldName: String, descriptor: String): Int =
    instructions.toArray().count { instruction ->
        instruction is FieldInsnNode && instruction.opcode == Opcodes.PUTFIELD &&
            instruction.owner == owner && instruction.name == fieldName && instruction.desc == descriptor
    }

private fun MethodNode.markedAssignmentStart(
    owner: String,
    plannedTransforms: List<PlannedTransform>,
): AbstractInsnNode {
    val slotsByField = plannedTransforms.associate { it.transform.fieldName to it.localSlot }
    val firstStore = instructions.toArray().firstOrNull { instruction ->
        instruction is FieldInsnNode && instruction.opcode == Opcodes.PUTFIELD &&
            instruction.owner == owner && instruction.name in slotsByField
    } as? FieldInsnNode ?: throw GradleException("No marked component assignment found in $owner")
    val valueLoad = firstStore.previousExecutable() as? VarInsnNode
    val receiverLoad = valueLoad?.previousExecutable() as? VarInsnNode
    if (valueLoad?.opcode != Opcodes.ALOAD || valueLoad.`var` != slotsByField[firstStore.name] ||
        receiverLoad?.opcode != Opcodes.ALOAD || receiverLoad.`var` != 0
    ) {
        throw GradleException("Unsupported marked component assignment shape for $owner.${firstStore.name}")
    }
    return receiverLoad
}

private fun MethodNode.hasExactPrologue(
    insertionPoint: AbstractInsnNode,
    plannedTransforms: List<PlannedTransform>,
): Boolean {
    val executablePrefix = instructions.toArray().takeWhile { it !== insertionPoint }.filter { it.opcode >= 0 }
    val instructionCount = plannedTransforms.sumOf { it.transform.instructionCount }
    if (executablePrefix.size < instructionCount) {
        return false
    }
    val candidate = executablePrefix.takeLast(instructionCount)
    var offset = 0
    for (planned in plannedTransforms) {
        if (!planned.transform.matches(candidate, offset, planned.localSlot)) {
            return false
        }
        offset += planned.transform.instructionCount
    }
    return true
}

private fun AbstractInsnNode.previousExecutable(): AbstractInsnNode? {
    var current = previous
    while (current != null && current.opcode < 0) {
        current = current.previous
    }
    return current
}