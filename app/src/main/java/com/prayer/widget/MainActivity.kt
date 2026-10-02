package com.prayer.widget

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val info = TextView(this).apply {
            text = "Prayer Times\n\nTo add the widget: long-press an empty spot on your home screen, tap Widgets, find Prayer Times and drag it out."
            textSize = 18f
            setTextColor(0xFFF4F7F5.toInt())
            setPadding(48, 96, 48, 48)
        }
        val btn = Button(this).apply {
            text = "Refresh widget"
            setOnClickListener {
                sendBroadcast(Intent(this@MainActivity, PrayerWidget::class.java).setAction(PrayerWidget.ACTION_REFRESH))
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0D1512.toInt())
            addView(info)
            addView(btn)
        })
    }
}
