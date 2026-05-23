package com.ambient.tvclock.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import com.ambient.tvclock.R

/**
 * About page. Read-only metadata. The license + source rows fire ACTION_VIEW
 * intents; on devices without a browser we toast a fallback so the surface
 * doesn't silently fail.
 */
class AboutSettingsFragment :
    SettingsScreenFragment(R.layout.fragment_settings_about) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<PrefRow>(R.id.prefAboutVersion).apply {
            label = getString(R.string.settings_about_version)
            setValue(getString(R.string.settings_about_version_value))
            isFocusable = false
        }

        view.findViewById<PrefRow>(R.id.prefAboutLicense).apply {
            label = getString(R.string.settings_about_license)
            setValue(getString(R.string.settings_about_license_value))
            setOnClickListener {
                openUrl("https://www.apache.org/licenses/LICENSE-2.0")
            }
        }

        view.findViewById<PrefRow>(R.id.prefAboutSource).apply {
            label = getString(R.string.settings_about_source)
            setValue(getString(R.string.settings_about_source_value))
            setOnClickListener {
                openUrl("https://${getString(R.string.settings_about_source_value)}")
            }
        }

        view.findViewById<PrefRow>(R.id.prefAboutSupport).apply {
            label = getString(R.string.settings_about_support)
            setValue(getString(R.string.settings_about_support_value))
            setOnClickListener {
                val email = getString(R.string.settings_about_support_value)
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:$email")
                }
                runCatching { startActivity(intent) }.onFailure {
                    Toast.makeText(requireContext(), email, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(requireContext(), url, Toast.LENGTH_LONG).show()
        }
    }
}
