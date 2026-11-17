package mightymich.morphe.patches.com.whatsapp

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

@Suppress("unused")
val unarchiveIndividualChatsPatch = bytecodePatch(
    name = "Unarchive individual chats",
    description = "Automatically unarchives individual chats on incoming messages. " +
        "Turn on Keep chats archived in WhatsApp to keep groups archived.",
    default = false,
) {
    compatibleWith(
        Compatibility(
            name = "WhatsApp",
            packageName = "com.whatsapp",
            apkFileType = ApkFileType.APK,
            targets = listOf(AppTarget(version = "2.26.43.76")),
            appIconColor = 0x25D366,
        ),
    )

    execute {
        val archiveUtility = classDefByStrings("archiveutil/setAutoUnarchiveChats=")
            .singleOrNull { it.type == "LX/F7B;" }
            ?: throw PatchException("WhatsApp archive utility fingerprint did not match.")
        val decision = mutableClassDefBy(archiveUtility).methods.singleOrNull {
            it.name == "A03" && it.returnType == "Z" &&
                it.parameterTypes.map(CharSequence::toString) == listOf("LX/0i5;", "LX/18V;")
        } ?: throw PatchException("WhatsApp message archive decision did not match.")

        val implementation = decision.implementation
            ?: throw PatchException("WhatsApp message archive decision has no bytecode.")
        val instructions = implementation.instructions.toList()
        val preferenceCall = instructions.indices.singleOrNull { index ->
            val instruction = instructions[index]
            val method = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            val result = instructions.getOrNull(index + 1)
            val branch = instructions.getOrNull(index + 2)
            instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                method?.definingClass == "LX/09S;" && method.name == "A1G" &&
                method.parameterTypes.isEmpty() && method.returnType == "Z" &&
                result?.opcode == Opcode.MOVE_RESULT && (result as? OneRegisterInstruction)?.registerA == 0 &&
                branch?.opcode == Opcode.IF_EQZ && (branch as? OneRegisterInstruction)?.registerA == 0
        } ?: throw PatchException("WhatsApp auto-unarchive preference read did not match.")

        // In the verified build v5 is the message's fromMe flag and p2 is the message.
        val fromMeRead = instructions.take(preferenceCall).singleOrNull {
            it.opcode == Opcode.IGET_BOOLEAN &&
                (it as? TwoRegisterInstruction)?.registerA == 5 &&
                (it as? ReferenceInstruction)?.reference?.toString() == "LX/1dV;->A02:Z"
        }
        if (implementation.registerCount != 13 || fromMeRead == null || preferenceCall != 292) {
            throw PatchException("Unsupported bytecode layout for WhatsApp 2.26.43.76.")
        }

        // Keep the original decision when auto-unarchive is already enabled.
        // Otherwise allow it only for a received message whose chat JID is a UserJid.
        decision.addInstructions(
            preferenceCall + 2,
            """
                if-nez v0, :archive_decision
                if-nez v5, :archive_decision
                iget-object v0, p2, LX/18V;->A0j:LX/1dV;
                iget-object v0, v0, LX/1dV;->A00:LX/0DY;
                instance-of v0, v0, Lcom/whatsapp/infra/core/jid/UserJid;
                :archive_decision
                nop
            """,
        )
    }
}
