package org.projectnia.app.ml

import android.content.Context
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PersonalizationStore(context: Context) {
    private val preferences = context.getSharedPreferences("nia_personalization_v1", Context.MODE_PRIVATE)

    fun load(): PersonalizationMemory = PersonalizationMemory().also { memory ->
        for (classId in 0 until NiaModel.CLASS_COUNT) {
            val encoded = preferences.getString("class_$classId", null) ?: continue
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            val floats = bytes.size / Float.SIZE_BYTES
            if (floats % NiaModel.EMBEDDING_SIZE != 0) continue
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            repeat(minOf(floats / NiaModel.EMBEDDING_SIZE, PersonalizationMemory.SHOTS_PER_SIGN)) {
                memory.addCorrection(classId, FloatArray(NiaModel.EMBEDDING_SIZE) { buffer.float })
            }
        }
    }

    fun save(memory: PersonalizationMemory) {
        val editor = preferences.edit().clear()
        memory.snapshot().forEachIndexed { classId, examples ->
            if (examples.isEmpty()) return@forEachIndexed
            val buffer = ByteBuffer.allocate(examples.size * NiaModel.EMBEDDING_SIZE * Float.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
            examples.forEach { embedding -> embedding.forEach(buffer::putFloat) }
            editor.putString("class_$classId", Base64.encodeToString(buffer.array(), Base64.NO_WRAP))
        }
        editor.apply()
    }
}

