/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly

import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.MenuItem
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import com.google.android.material.checkbox.MaterialCheckBox
import org.lineageos.jelly.utils.SharedPreferencesExt
import kotlin.reflect.safeCast

class SettingsActivity : AppCompatActivity() {
    // Views
    private val toolbar by lazy { findViewById<Toolbar>(R.id.toolbar) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings, SettingsFragment())
                .commit()
        }

        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }
    }

    // D-pad navigation. The list's instant focus correction (LayoutManager.
    // requestChildRectangleOnScreen) is what jerks the list and snaps the app bar in the same
    // frame, so the fragment's layout manager swallows it (see SettingsFragment) and all
    // motion here is the framework's smooth scroll — whose frames pass through the same nested
    // scrolling a mouse wheel uses, collapsing/expanding the app bar smoothly as a side
    // effect. Focus therefore never moves the list, and the list never jumps.
    private var lastFocusedPos = -1
    private var navGen = 0
    private var glideListener:
        androidx.recyclerview.widget.RecyclerView.OnScrollListener? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
        ) {
            val rv = findRecyclerView(findViewById(R.id.settings))
            if (rv != null && (rv.findFocus() != null || lastFocusedPos >= 0) &&
                navigate(rv, event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
            ) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun navigate(
        rv: androidx.recyclerview.widget.RecyclerView,
        down: Boolean
    ): Boolean {
        val adapter = rv.adapter ?: return false
        val dir = if (down) 1 else -1
        var pos = focusedRowPos(rv)
        if (pos < 0) pos = lastFocusedPos
        if (pos < 0) return false
        var target = pos + dir
        while (target in 0 until adapter.itemCount &&
            rv.findViewHolderForAdapterPosition(target)?.itemView?.isFocusable == false
        ) {
            target += dir
        }
        if (target !in 0 until adapter.itemCount) return true
        lastFocusedPos = target
        navGen++
        glideListener?.let { rv.removeOnScrollListener(it) }
        glideListener = null
        rv.stopScroll()
        // Move the highlight first; with the focus correction disabled above, focusing a
        // clipped row no longer yanks the list. A row that is not on screen gets the focus
        // once the glide brings it in.
        rv.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus()
        glideToVisible(rv, target, 0, navGen)
        return true
    }

    private fun glideToVisible(
        rv: androidx.recyclerview.widget.RecyclerView,
        target: Int,
        pass: Int,
        gen: Int
    ) {
        if (gen != navGen) return
        val out = if (pass > 8) 0 else distanceToVisible(rv, target)
        if (out == 0) {
            rv.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus()
            return
        }
        rv.smoothScrollBy(0, out)
        val listener = object :
            androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                newState: Int
            ) {
                if (newState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE) {
                    recyclerView.removeOnScrollListener(this)
                    if (glideListener === this) glideListener = null
                    if (gen == navGen) glideToVisible(rv, target, pass + 1, gen)
                }
            }
        }
        glideListener?.let { rv.removeOnScrollListener(it) }
        glideListener = listener
        rv.addOnScrollListener(listener)
    }

    private fun focusedRowPos(rv: androidx.recyclerview.widget.RecyclerView): Int {
        val focused = rv.findFocus() ?: return -1
        if (focused === rv) return -1
        var child: View = focused
        while (child.parent !== rv && child.parent is View) {
            child = child.parent as View
        }
        return if (child.parent === rv) rv.getChildAdapterPosition(child) else -1
    }

    private fun distanceToVisible(
        rv: androidx.recyclerview.widget.RecyclerView,
        target: Int
    ): Int {
        val row = rv.findViewHolderForAdapterPosition(target)?.itemView
        if (row != null) {
            // The app bar is a sibling drawn on top of the list, so no stock visibility API
            // notices a row hiding behind it. Compare the row's actual window position with
            // the app bar's actual bottom edge: nothing may rest behind the header.
            val appBar = findAppBar(window.decorView)
            if (appBar != null) {
                val rowLoc = IntArray(2)
                row.getLocationInWindow(rowLoc)
                val barLoc = IntArray(2)
                appBar.getLocationInWindow(barLoc)
                if (rowLoc[1] < barLoc[1] + appBar.height) {
                    return rowLoc[1] - (barLoc[1] + appBar.height)
                }
            }
            return when {
                row.bottom > rv.height -> row.bottom - rv.height
                row.top < 0 -> row.top
                else -> 0
            }
        }
        val lm = rv.layoutManager as?
            androidx.recyclerview.widget.LinearLayoutManager ?: return 0
        val last = lm.findLastVisibleItemPosition()
        if (target > last && last >= 0) {
            val ref = lm.findViewByPosition(last) ?: return 0
            return (ref.bottom - rv.height).coerceAtLeast(0) + ref.height * (target - last)
        }
        val first = lm.findFirstVisibleItemPosition()
        if (target < first && first >= 0) {
            val ref = lm.findViewByPosition(first) ?: return 0
            return ref.top.coerceAtMost(0) - ref.height * (first - target)
        }
        return 0
    }

    private fun findAppBar(
        root: View?
    ): com.google.android.material.appbar.AppBarLayout? {
        if (root is com.google.android.material.appbar.AppBarLayout) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                findAppBar(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun findRecyclerView(
        root: View?
    ): androidx.recyclerview.widget.RecyclerView? {
        if (root is androidx.recyclerview.widget.RecyclerView) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                findRecyclerView(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }

        else -> {
            super.onOptionsItemSelected(item)
        }
    }

    class SettingsFragment : PreferenceFragmentCompat(), Preference.OnPreferenceChangeListener {
        private val sharedPreferencesExt by lazy { SharedPreferencesExt(requireContext()) }

        // The instant focus correction in LayoutManager.requestChildRectangleOnScreen is the
        // mechanism that jerks the list and snaps the collapsing header the moment focus
        // touches a clipped or off-screen row. Swallow it here: focus never scrolls the list.
        // All motion is then the activity's smooth glide, whose frames scroll through the
        // nested scrolling a wheel uses, so the header collapses and expands natively inside
        // the same smooth motion.
        override fun onCreateLayoutManager():
            androidx.recyclerview.widget.RecyclerView.LayoutManager {
            return object :
                androidx.recyclerview.widget.LinearLayoutManager(requireContext()) {
                override fun requestChildRectangleOnScreen(
                    parent: androidx.recyclerview.widget.RecyclerView,
                    child: View,
                    description: Rect,
                    immediate: Boolean
                ): Boolean = true

                override fun requestChildRectangleOnScreen(
                    parent: androidx.recyclerview.widget.RecyclerView,
                    child: View,
                    description: Rect,
                    immediate: Boolean,
                    unconsumed: Boolean
                ): Boolean = true
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            setDivider(ColorDrawable(Color.TRANSPARENT))
            setDividerHeight(0)
        }

        override fun onCreatePreferences(savedInstance: Bundle?, rootKey: String?) {
            // Load the preferences from an XML resource
            setPreferencesFromResource(R.xml.settings, rootKey)

            findPreference<Preference>("key_home_page")?.let {
                it.onPreferenceChangeListener = this
                it.summary = homePagePrefSummary()
            }
            if (resources.getBoolean(R.bool.is_tablet)) {
                findPreference<SwitchPreference>("key_reach_mode")?.let {
                    preferenceScreen.removePreference(it)
                }
            }
            findPreference<SwitchPreference>("key_web_debugging")?.onPreferenceChangeListener =
                Preference.OnPreferenceChangeListener { _, newValue ->
                    WebView.setWebContentsDebuggingEnabled(newValue as Boolean)
                    true
                }
        }

        override fun onPreferenceChange(preference: Preference, value: Any?): Boolean {
            val stringValue = value.toString()

            ListPreference::class.safeCast(preference)?.also {
                val prefIndex = it.findIndexOfValue(stringValue)
                if (prefIndex >= 0) {
                    preference.summary = it.entries[prefIndex]
                }
            } ?: run {
                preference.summary = stringValue
            }
            return true
        }

        override fun onPreferenceTreeClick(preference: Preference): Boolean {
            return when (preference.key) {
                "key_home_page" -> {
                    editHomePage(preference)
                    true
                }

                "key_cookie_clear" -> {
                    CookieManager.getInstance().removeAllCookies(null)
                    Toast.makeText(
                        preference.context, getString(R.string.pref_cookie_clear_done),
                        Toast.LENGTH_LONG
                    ).show()
                    true
                }

                else -> {
                    super.onPreferenceTreeClick(preference)
                }
            }
        }

        private fun editHomePage(preference: Preference) {
            val builder = AlertDialog.Builder(preference.context)
            val alertDialog = builder.create()
            val inflater = alertDialog.layoutInflater
            val homepageView = inflater.inflate(
                R.layout.dialog_homepage_edit,
                LinearLayout(preference.context)
            )
            val homepageUrlEditText = homepageView.findViewById<EditText>(R.id.homepageUrlEditText)
            homepageUrlEditText.setText(sharedPreferencesExt.homePage)
            val homepageAutoload = homepageView.findViewById<MaterialCheckBox>(
                R.id.homePageAutoloadCheckBox
            )
            homepageAutoload.isChecked = sharedPreferencesExt.homePageAutoload
            homepageAutoload.setOnCheckedChangeListener { _, checked ->
                homepageUrlEditText.isEnabled = checked
            }
            homepageUrlEditText.isEnabled = homepageAutoload.isChecked
            builder.setTitle(R.string.pref_start_page_dialog_title)
                .setMessage(R.string.pref_start_page_dialog_message)
                .setView(homepageView)
                .setPositiveButton(
                    android.R.string.ok
                ) { _: DialogInterface?, _: Int ->
                    val url = homepageUrlEditText.text.toString().ifEmpty {
                        sharedPreferencesExt.defaultHomePage
                    }
                    sharedPreferencesExt.homePage = url
                    sharedPreferencesExt.homePageAutoload = homepageAutoload.isChecked
                    preference.summary = homePagePrefSummary()
                }
                .setNeutralButton(
                    R.string.pref_start_page_dialog_reset
                ) { _: DialogInterface?, _: Int ->
                    val url = sharedPreferencesExt.defaultHomePage
                    val autoload = sharedPreferencesExt.defaultHomePageAutoload
                    sharedPreferencesExt.homePage = url
                    sharedPreferencesExt.homePageAutoload = autoload
                    preference.summary = homePagePrefSummary()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun homePagePrefSummary() =
            if (sharedPreferencesExt.homePageAutoload) {
                sharedPreferencesExt.homePage
            } else {
                getString(R.string.pref_start_page_autoload_disabled)
            }
    }
}
