package com.smartguard.debug

import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.smartguard.SmartGuardApp
import com.smartguard.data.repository.ProfileRepository
import com.smartguard.recognition.matcher.FaceMatcher
import com.smartguard.recognition.model.FaceMatchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * DEBUG BUILDS ONLY. Measures recognition quality on the REAL enrolled embeddings (decrypted
 * on-device via the Keystore) without anyone in front of the camera:
 *  - genuine similarity: samples of the same person vs each other
 *  - impostor similarity: samples of different people
 *  - leave-one-out identification through the production FaceMatcher (threshold + margin rule)
 * Logs only scores (tag "SG-RecogProbe"), never the embeddings themselves.
 *
 *   adb shell am start -n com.smartguard/.debug.RecognitionProbeActivity
 */
class RecognitionProbeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val out = TextView(this).apply { setPadding(32, 32, 32, 32); setTextIsSelectable(true) }
        setContentView(ScrollView(this).apply { addView(out) })

        lifecycleScope.launch {
            val lines = withContext(Dispatchers.IO) { runProbe() }
            lines.forEach { Log.i(TAG, it); out.append(it + "\n") }
        }
    }

    private suspend fun runProbe(): List<String> {
        val out = mutableListOf<String>()
        val repo = SmartGuardApp.instance.profileRepository
        val profiles = repo.getAllProfiles().associateBy { it.id }
        val all = repo.getAllDecryptedEmbeddings()
        val matcher = FaceMatcher()
        val byProfile = all.groupBy { it.profileId }

        out += "profiles=${profiles.values.map { "${it.name}(${it.role})" }} embeddings=${all.size} dims=${all.map { it.vector.size }.distinct()}"
        out += "threshold=${FaceMatcher.SIMILARITY_THRESHOLD} margin=${FaceMatcher.MARGIN_THRESHOLD}"

        // Genuine (same person) pairs.
        for ((pid, list) in byProfile) {
            val sims = pairs(list, list, samePerson = true).map { (a, b) -> matcher.cosineSimilarity(a.vector, b.vector) }
            out += "GENUINE ${profiles[pid]?.name}: ${stats(sims)}"
        }

        // Impostor (different people) pairs.
        val ids = byProfile.keys.toList()
        for (i in ids.indices) for (j in i + 1 until ids.size) {
            val sims = pairs(byProfile.getValue(ids[i]), byProfile.getValue(ids[j]), samePerson = false)
                .map { (a, b) -> matcher.cosineSimilarity(a.vector, b.vector) }
            val accepted = sims.count { it >= FaceMatcher.SIMILARITY_THRESHOLD }
            out += "IMPOSTOR ${profiles[ids[i]]?.name} vs ${profiles[ids[j]]?.name}: ${stats(sims)} " +
                "pairs>=threshold=$accepted/${sims.size}"
        }

        // Leave-one-out: identify each sample against everyone else's samples via the real matcher.
        var correct = 0
        var wrong = 0
        var rejected = 0
        for (probe in all) {
            val gallery = all.filter { it.embeddingId != probe.embeddingId }
            when (val r = matcher.findBestMatch(probe.vector, gallery, profiles).result) {
                is FaceMatchResult.PendingMatch ->
                    if (r.candidateProfile.id == probe.profileId) correct++ else wrong++
                else -> rejected++
            }
        }
        out += "LEAVE-ONE-OUT identification: correct=$correct wrong(false accept)=$wrong rejected=$rejected of ${all.size}"
        out += "DONE"
        return out
    }

    private fun pairs(
        a: List<ProfileRepository.DecryptedEmbedding>,
        b: List<ProfileRepository.DecryptedEmbedding>,
        samePerson: Boolean
    ): List<Pair<ProfileRepository.DecryptedEmbedding, ProfileRepository.DecryptedEmbedding>> =
        if (samePerson) {
            a.indices.flatMap { i -> (i + 1 until a.size).map { j -> a[i] to a[j] } }
        } else {
            a.flatMap { x -> b.map { y -> x to y } }
        }

    private fun stats(v: List<Float>): String =
        if (v.isEmpty()) "n=0"
        else "n=${v.size} min=%.3f mean=%.3f max=%.3f".format(v.min(), v.average(), v.max())

    companion object {
        private const val TAG = "SG-RecogProbe"
    }
}
