package com.github.razertexz

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.*
import com.aliucord.utils.DimenUtils

// Discord Imports
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.MessageEntry
import com.discord.utilities.view.text.SimpleDraweeSpanTextView

// Resources
import com.lytefast.flexinput.R
import rx.functions.Action0

@AliucordPlugin(requiresRestart = true)
class Main : Plugin() {
    private val ID_BTN_COPY_TEXT = 1001 
    private val ID_BTN_COPY_LINK = 1002

    // State tracking variables
    private var wasAtBottom = false
    private var oldListSize = 0

    override fun start(ctx: Context) {
        
        // ========================================================================
        // Feature 1: Anti-Auto-Scroll
        // ========================================================================
        
        // 1. Hook scrollToMessageId to prevent forced scrolling when reading history
        patcher.before<WidgetChatListAdapter>(
            "scrollToMessageId", 
            java.lang.Long.TYPE, // 使用 java.lang.Long.TYPE 確保是 primitive long (J)
            Action0::class.java            
        ) {
            val adapter = it.thisObject as WidgetChatListAdapter
            val layoutManager = adapter.layoutManager

            if (layoutManager is LinearLayoutManager) {
                // 0 is bottom in reverse layout
                val firstVisible = layoutManager.findFirstVisibleItemPosition()
                
                // If user is scrolled up more than 5 items, block the scroll command
                if (firstVisible > 5) {
                    it.result = null
                }
            }
        }

        val dataClass = try {
            com.discord.widgets.chat.list.adapter.WidgetChatListAdapter.Data::class.java
        } catch (e: Throwable) {
            Class.forName("com.discord.widgets.chat.list.adapter.WidgetChatListAdapter\$Data")
        }

        // 2. Hook setData (before) to record state
        patcher.before<WidgetChatListAdapter>(
            "setData",
            dataClass
        ) {
            val adapter = it.thisObject as WidgetChatListAdapter
            val layoutManager = adapter.layoutManager as? LinearLayoutManager ?: return@before
            
            // Check if we are currently at the bottom (index 0)
            wasAtBottom = layoutManager.findFirstVisibleItemPosition() == 0
            
            // Record current item count
            oldListSize = adapter.itemCount
        }

        // 3. Hook setData (after) to correct position if needed
        patcher.after<WidgetChatListAdapter>(
            "setData",
            dataClass
        ) {
            val adapter = it.thisObject as WidgetChatListAdapter
            val layoutManager = adapter.layoutManager as? LinearLayoutManager ?: return@after
            
            val newListSize = adapter.itemCount
            
            val isNewItemAdded = newListSize > oldListSize

            // If we were at bottom AND a new item was added
            // Force scroll to index 1 (the previous latest message) to hide the new one
            if (wasAtBottom && isNewItemAdded) {
                adapter.recycler.post {
                    layoutManager.scrollToPositionWithOffset(1, 0)
                }
            }
        }

        // ========================================================================
        // Feature 2: Copy Buttons
        // ========================================================================
        val btnSize = DimenUtils.dpToPx(40)
        val btnPadding = DimenUtils.dpToPx(8)
        val btnTopMargin = DimenUtils.dpToPx(-5) 
        val btnGap = DimenUtils.dpToPx(4)

        val iconText = ctx.getDrawable(R.e.ic_copy_24dp)?.mutate()
        iconText?.setTint(Color.CYAN)

        val iconLink = ctx.getDrawable(R.e.ic_copy_24dp)?.mutate()
        iconLink?.setTint(Color.RED)

        patcher.after<WidgetChatListAdapterItemMessage>(
            "processMessageText", 
            SimpleDraweeSpanTextView::class.java, 
            MessageEntry::class.java
        ) {
            val messageEntry = it.args[1] as MessageEntry
            val holder = it.thisObject as RecyclerView.ViewHolder
            val root = holder.itemView as ConstraintLayout

            // --- 1. Blue Button (Copy Text) ---
            var btnText = root.findViewById<ImageView>(ID_BTN_COPY_TEXT)
            if (btnText == null) {
                btnText = ImageView(root.context).apply {
                    id = ID_BTN_COPY_TEXT
                    if (iconText != null) setImageDrawable(iconText)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(btnPadding, btnPadding, btnPadding, btnPadding)
                    
                    layoutParams = ConstraintLayout.LayoutParams(btnSize, btnSize).apply {
                        topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                        endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                        topMargin = btnTopMargin 
                        rightMargin = 0
                    }
                }
                root.addView(btnText)
            }
            btnText.visibility = View.VISIBLE
            btnText.setOnClickListener {
                val content = messageEntry.message.content
                Utils.setClipboard(content, content)
                Utils.showToast("Copied Text!")
            }

            // --- 2. Red Button (Copy Link) ---
            var targetUrl: String? = null
            val embeds = messageEntry.message.embeds
            if (embeds.isNotEmpty()) {
                val embed = embeds[0]
                try {
                    val urlField = embed::class.java.getDeclaredField("url")
                    urlField.isAccessible = true
                    targetUrl = urlField.get(embed) as String?
                } catch (e: Exception) {}
            }

            var btnLink = root.findViewById<ImageView>(ID_BTN_COPY_LINK)
            
            if (targetUrl != null && targetUrl!!.isNotEmpty()) {
                if (btnLink == null) {
                    btnLink = ImageView(root.context).apply {
                        id = ID_BTN_COPY_LINK
                        if (iconLink != null) setImageDrawable(iconLink)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setPadding(btnPadding, btnPadding, btnPadding, btnPadding)

                        layoutParams = ConstraintLayout.LayoutParams(btnSize, btnSize).apply {
                            topToTop = ID_BTN_COPY_TEXT 
                            endToStart = ID_BTN_COPY_TEXT 
                            rightMargin = btnGap 
                        }
                    }
                    root.addView(btnLink)
                }
                btnLink.visibility = View.VISIBLE
                val finalUrl = targetUrl
                btnLink.setOnClickListener {
                    Utils.setClipboard(finalUrl, finalUrl)
                    Utils.showToast("Copied Link!")
                }
            } else {
                btnLink?.visibility = View.GONE
            }
        }
    }

    override fun stop(ctx: Context) = patcher.unpatchAll()
}
