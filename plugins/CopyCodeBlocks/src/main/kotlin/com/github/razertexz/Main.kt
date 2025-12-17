package com.github.razertexz

import androidx.constraintlayout.widget.ConstraintLayout
import android.content.Context
import android.widget.ImageView
import android.view.View
import android.view.MotionEvent
import android.graphics.Color
import androidx.recyclerview.widget.RecyclerView
import android.view.ViewGroup

// 引入 Fragment 以解決 onViewCreated 找不到的問題
import androidx.fragment.app.Fragment 

import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.*
import com.aliucord.Utils
import com.aliucord.utils.DimenUtils

import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.MessageEntry
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
// 移除 WidgetChatList 的 Import，改用字串判斷，避免類別參照錯誤

import com.lytefast.flexinput.R

@AliucordPlugin(requiresRestart = true)
class Main : Plugin() {
    private val ID_BTN_COPY_TEXT = 1001 
    private val ID_BTN_COPY_LINK = 1002
    
    private var lastJumpRequestTime = 0L

    override fun start(ctx: Context) {
        // ==========================================
        //  功能 0: 監聽「跳到底部」按鈕 (修復崩潰版)
        // ==========================================
        
        // 【關鍵修改】改成 Hook 'Fragment'，而不是 'WidgetChatList'
        // 因為 WidgetChatList 沒有定義 onViewCreated，但 Fragment 一定有。
        patcher.after<Fragment>("onViewCreated", View::class.java, android.os.Bundle::class.java) {
            // 檢查當前的物件是不是聊天室 (WidgetChatList)
            // 使用字串名稱比對，最安全，不會有 Import 錯誤
            if (it.thisObject::class.java.name == "com.discord.widgets.chat.list.WidgetChatList") {
                val view = it.args[0] as View
                val scrollBtnId = Utils.getResId("chat_list_scroll_to_bottom", "id")
                
                if (scrollBtnId != 0) {
                    val scrollBtn = view.findViewById<View>(scrollBtnId)
                    if (scrollBtn != null) {
                        scrollBtn.setOnTouchListener { _, event ->
                            if (event.action == MotionEvent.ACTION_DOWN) {
                                // 發放通行證
                                lastJumpRequestTime = System.currentTimeMillis()
                            }
                            false 
                        }
                    }
                }
            }
        }

        // ==========================================
        //  功能 1: 智慧型防捲動 (Anti-Scroll)
        // ==========================================
        
        // 1. 攔截 scrollToPosition
        patcher.before<RecyclerView>(
            "scrollToPosition", 
            Int::class.javaPrimitiveType!! 
        ) {
            try {
                // 檢查通行證 (1.5秒內)
                val isAllowed = (System.currentTimeMillis() - lastJumpRequestTime) < 1500
                if (!isAllowed) {
                    val adapter = (it.thisObject as RecyclerView).adapter
                    // 確認是聊天室 Adapter 才攔截
                    if (adapter != null && adapter::class.java.name.contains("WidgetChatListAdapter")) {
                        it.result = null // 阻止執行
                    }
                }
            } catch (e: Exception) {}
        }

        // 2. 攔截 smoothScrollToPosition (Discord 主要用這個)
        patcher.before<RecyclerView>(
            "smoothScrollToPosition", 
            Int::class.javaPrimitiveType!! 
        ) {
            try {
                val isAllowed = (System.currentTimeMillis() - lastJumpRequestTime) < 1500
                if (!isAllowed) {
                    val adapter = (it.thisObject as RecyclerView).adapter
                    if (adapter != null && adapter::class.java.name.contains("WidgetChatListAdapter")) {
                        it.result = null
                    }
                }
            } catch (e: Exception) {}
        }

        // ==========================================
        //  功能 2: 複製按鈕 (Copy Buttons)
        // ==========================================
        
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

            // --- 藍色按鈕 ---
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
                Utils.showToast("已複製文字！")
            }

            // --- 紅色按鈕 ---
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
                    Utils.showToast("已複製連結！")
                }
            } else {
                btnLink?.visibility = View.GONE
            }
        }
    }

    override fun stop(ctx: Context) = patcher.unpatchAll()
}
