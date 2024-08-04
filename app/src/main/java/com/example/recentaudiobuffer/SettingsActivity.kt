package com.example.recentaudiobuffer

import android.content.Context
import android.media.AudioFormat
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat


class SettingsActivity : AppCompatActivity() {
    private val logTag = "SettingsActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, SettingsFragment())
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    interface SettingsCallback {
        fun onSettingsUpdated(sampleRate: Int, bitDepth: BitDepth)
    }

    class SettingsFragment : PreferenceFragmentCompat(), SettingsCallback {
        private val logTag = "SettingsFragment"

        override fun onSettingsUpdated(sampleRate: Int, bitDepth: BitDepth) {
            val sharedPrefs = activity?.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val editor = sharedPrefs?.edit()
            editor?.putInt("sample_rate", sampleRate)
            editor?.putString(
                "bit_depth",
                "${bitDepth.bytes},${bitDepth.encodingEnum}"
            ) // Store as string
            editor?.apply()
            Log.e(logTag, "onSettingsUpdated to: $sampleRate, $bitDepth")
        }

        // In SettingsFragment
        private val sampleRateSpinner = view?.findViewById<Spinner>(R.id.sample_rate_spinner)
        private val bitDepthSpinner = view?.findViewById<Spinner>(R.id.bit_depth_spinner)

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey)


            sampleRateSpinner?.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long
                    ) {
                        val selectedSampleRate =
                            sampleRates[parent?.getItemAtPosition(position).toString()] ?: 22050
                        // Get the current bit depth from SharedPreferences
                        val sharedPrefs =
                            activity?.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                        val bitDepthString =
                            sharedPrefs?.getString("bit_depth", "8,1") // Default to 8-bit
                        val (bytes, encoding) = bitDepthString?.split(",")?.map { it.toInt() }
                            ?: listOf(8, 1)
                        val currentBitDepth = BitDepth(bytes, encoding)
                        (this@SettingsFragment as? SettingsCallback)?.onSettingsUpdated(
                            selectedSampleRate,
                            currentBitDepth
                        )
                    }


                    override fun onNothingSelected(parent: AdapterView<*>?) {
                        // Handle case where nothing is selected
                    }
                }

            bitDepthSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    val selectedBitDepth =
                        bitDepths[parent?.getItemAtPosition(position).toString()] ?: BitDepth(
                            8,
                            AudioFormat.ENCODING_PCM_8BIT
                        )
                    // Get the current sample rate from SharedPreferences
                    val sharedPrefs =
                        activity?.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                    val currentSampleRate = sharedPrefs?.getInt("sample_rate", 22050) ?: 22050

                    (this@SettingsFragment as? SettingsCallback)?.onSettingsUpdated(
                        currentSampleRate,
                        selectedBitDepth
                    )
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                    // Handle case where nothing is selected
                }
            }
        }
    }
}