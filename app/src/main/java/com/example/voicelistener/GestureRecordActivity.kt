package com.example.voicelistener

import android.gesture.Gesture
import android.gesture.GestureOverlayView
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class GestureRecordActivity : AppCompatActivity() {

    private lateinit var gestureManager: GestureManager
    private lateinit var gestureOverlay: GestureOverlayView
    private lateinit var actionSpinner: Spinner
    private var currentGesture: Gesture? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gestureManager = GestureManager(this)

        val actions = GestureManager.getAllActionLabels(this).entries.toList()

        // Outer layout: top controls, gesture area (takes remaining space), bottom controls
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        // Title + Action Spinner (top, fixed)
        outer.addView(TextView(this).apply {
            text = "Geste aufnehmen"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })

        outer.addView(TextView(this).apply {
            text = "Aktion auswählen:"
            setPadding(0, 24, 0, 8)
        })

        actionSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@GestureRecordActivity,
                android.R.layout.simple_spinner_item,
                actions.map { it.value }
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        }
        outer.addView(actionSpinner)

        outer.addView(TextView(this).apply {
            text = "Zeichne eine Geste (Finger heben = fertig):"
            setPadding(0, 24, 0, 8)
        })

        // Status indicator
        val statusText = TextView(this).apply {
            text = ""
            setTextColor(0xFF4CAF50.toInt())
            setPadding(0, 0, 0, 8)
        }
        outer.addView(statusText)

        // Gesture drawing area — NOT inside ScrollView, takes remaining space
        gestureOverlay = GestureOverlayView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            // SINGLE stroke type fires onGesturePerformed immediately when finger lifts
            gestureStrokeType = GestureOverlayView.GESTURE_STROKE_TYPE_SINGLE
            setBackgroundColor(0x18000000)
            isEventsInterceptionEnabled = true
            addOnGesturePerformedListener { _, gesture ->
                currentGesture = gesture
                // Clear preview background when drawing a new gesture
                background = null
                setBackgroundColor(0x18000000)
                statusText.text = "Neue Geste erkannt! Tippe 'Speichern' oder zeichne erneut."
            }
        }
        outer.addView(gestureOverlay)

        // Buttons (bottom, fixed)
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, 16, 0, 0)
        }

        buttonRow.addView(Button(this).apply {
            text = "Löschen"
            setOnClickListener {
                gestureOverlay.clear(false)
                gestureOverlay.background = null
                gestureOverlay.setBackgroundColor(0x18000000)
                currentGesture = null
                statusText.text = ""
            }
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                marginEnd = 8
            }
        })

        buttonRow.addView(Button(this).apply {
            text = "Abbrechen"
            setOnClickListener { finish() }
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                marginEnd = 8
            }
        })

        buttonRow.addView(Button(this).apply {
            text = "Speichern"
            setOnClickListener {
                val gesture = currentGesture
                if (gesture == null) {
                    Toast.makeText(this@GestureRecordActivity, "Bitte erst eine Geste zeichnen", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val actionKey = actions[actionSpinner.selectedItemPosition].key
                val name = "custom_${System.currentTimeMillis()}"
                gestureManager.addGesture(name, gesture)
                gestureManager.setActionForGesture(name, actionKey)
                Toast.makeText(this@GestureRecordActivity, "Geste gespeichert", Toast.LENGTH_SHORT).show()
                finish()
            }
        })
        outer.addView(buttonRow)

        // Existing gestures in a scrollable area at the bottom
        val existingNames = gestureManager.getGestureNames()
        if (existingNames.isNotEmpty()) {
            outer.addView(TextView(this).apply {
                text = "Gespeicherte Gesten:"
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 24, 0, 8)
            })

            val listLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }

            for (name in existingNames) {
                val action = gestureManager.getActionForGesture(name) ?: "?"
                val label = GestureManager.getAllActionLabels(this@GestureRecordActivity)[action] ?: action
                val gesture = gestureManager.getGesture(name)

                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 8, 0, 8)
                }

                // Thumbnail of the gesture
                if (gesture != null) {
                    val thumbSize = (48 * resources.displayMetrics.density).toInt()
                    val bitmap = gesture.toBitmap(thumbSize, thumbSize, 4, Color.WHITE)
                    row.addView(ImageView(this).apply {
                        setImageBitmap(bitmap)
                        setBackgroundColor(0x20000000)
                        layoutParams = LinearLayout.LayoutParams(thumbSize, thumbSize).apply {
                            marginEnd = 16
                        }
                    })
                }

                row.addView(TextView(this).apply {
                    text = label
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                })

                row.addView(Button(this).apply {
                    text = "X"
                    textSize = 12f
                    minimumWidth = 0
                    minWidth = 0
                    setPadding(24, 8, 24, 8)
                    setOnClickListener {
                        gestureManager.removeGesture(name)
                        recreate()
                    }
                })

                // Click on row to preview gesture (read-only, does NOT set currentGesture)
                if (gesture != null) {
                    row.isClickable = true
                    row.setOnClickListener {
                        gestureOverlay.clear(false)
                        currentGesture = null // Clear so "Speichern" won't save a duplicate
                        gestureOverlay.post {
                            val overlayWidth = gestureOverlay.width.toFloat()
                            val overlayHeight = gestureOverlay.height.toFloat()
                            val preview = gesture.toBitmap(overlayWidth.toInt(), overlayHeight.toInt(), 6, Color.YELLOW)
                            gestureOverlay.background = android.graphics.drawable.BitmapDrawable(resources, preview)
                            statusText.text = "Vorschau: $label (zeichne neu um zu überschreiben)"
                        }
                    }
                }

                listLayout.addView(row)
            }

            val scroll = ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    topMargin = 8
                }
                // Max height for the list so it doesn't push gesture area away
                addView(listLayout)
            }
            outer.addView(scroll)
        }

        setContentView(outer)
    }
}
