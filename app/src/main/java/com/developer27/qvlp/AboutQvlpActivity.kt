package com.developer27.qvlp

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.appcompat.app.AppCompatActivity
import com.developer27.qvlp.databinding.ActivityAboutQvlpBinding

class AboutQvlpActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutQvlpBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutQvlpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.titleContainer.setOnClickListener { openProjectWebsite() }

        // The version shown is the installed app's own, so it cannot fall behind a release.
        @Suppress("DEPRECATION")
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()
        binding.textViewVersion.text = SpannableStringBuilder()
            .append(getString(R.string.project_version_label), StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            .append(" ").append(version)

        // Open the university website when its logo is tapped.
        binding.umLogo.setOnClickListener {
            val url = "https://umich.edu/"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        }
    }
}
