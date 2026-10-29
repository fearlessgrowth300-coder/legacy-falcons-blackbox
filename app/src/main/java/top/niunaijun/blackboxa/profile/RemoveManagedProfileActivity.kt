package top.niunaijun.blackboxa.profile

import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import top.niunaijun.blackboxa.R

/**
 * Runs in the work profile. wipeData from the profile owner removes that user only.
 * The personal profile cannot call it, so the status screen starts this activity there.
 */
class RemoveManagedProfileActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val controller = ManagedProfileController(this)
        val ink = ProfileStatusActivity.readableTextColor(this)
        val background = ContextCompat.getColor(this, R.color.blackbox_background)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(background)
        }
        if (!controller.isProfileOwner()) {
            column.addView(android.widget.TextView(this).apply {
                setTextColor(ink)
                text = "This screen only works inside the work profile. From the personal side, " +
                    "use Profile separation and tap Remove profile and set up again."
            })
            setContentView(android.widget.ScrollView(this).apply {
                setBackgroundColor(background)
                addView(column)
            })
            return
        }
        column.addView(android.widget.TextView(this).apply {
            setTextColor(ink)
            text = "Remove the work profile? Apps and accounts inside it are deleted. " +
                "Blackbox on the personal side stays. You can create the profile again afterwards."
        })
        column.addView(android.widget.Button(this).apply {
            text = "Remove work profile"
            setOnClickListener {
                if (!ManagedProfileFinalizer.wipeIfOwner(this@RemoveManagedProfileActivity)) {
                    android.widget.Toast.makeText(
                        this@RemoveManagedProfileActivity,
                        "Android did not remove the work profile.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        })
        column.addView(android.widget.Button(this).apply {
            text = "Cancel"
            setOnClickListener { finish() }
        })
        setContentView(android.widget.ScrollView(this).apply {
            setBackgroundColor(background)
            addView(column)
        })
    }
}
