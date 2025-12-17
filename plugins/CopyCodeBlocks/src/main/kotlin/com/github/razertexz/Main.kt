package com.github.razertexz

import androidx.constraintlayout.widget.ConstraintLayout
import android.content.Context
import android.widget.ImageView
import android.view.View
import android.view.MotionEvent
import android.graphics.Color
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import android.view.ViewGroup

import androidx.fragment.app.Fragment 

import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.*
import com.aliucord.Utils
import com.aliucord.utils.DimenUtils

import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.MessageEntry
import com.discord.utilities.view.text.SimpleDraweeSpanTextView

import com.lytefast.flexinput.R

@AliucordPlugin(requiresRestart = true)
class Main : Plugin() {
    private val ID_BTN_COPY_TEXT = 1001 
    private val ID_BTN_COPY_LINK = 1002
    
    // 通行證時間戳記
    private var lastJumpRequestTime = 0L

    // 聊天室 RecyclerView 的資源 ID (快取起來)
    private var chatListRecyclerId = 0

    override fun start(ctx: Context) {
        // 取得聊天室 RecyclerView 的 ID
        chatListRecyclerId = Utils.getResId("chat_list_recycler_view", "id")

        // ==========================================
        //  功能 0: 監聽「跳到底部」按鈕
        // ==========================================
        patcher.after<Fragment>("onViewCreated", View::class.java, android.os.Bundle::class.java) {
            // 只在聊天室 Fragment 執行
            if (it.thisObject::class.java.name.contains("WidgetChatList")) {
                val view = it.args[0] as View
                
                // 1. 綁定「跳到底部」按鈕
                val scrollBtnId = Utils.getResId("chat_list_scroll_to_bottom", "id")
                if (scrollBtnId != 0) {
                    val scrollBtn = view.findViewById<View>(scrollBtnId)
                    if (scrollBtn != null) {
                        scrollBtn.setOnTouchListener { _, event ->
                            if (event.action == MotionEvent.ACTION_DOWN) {
                                // 給予 2 秒的捲動權限
                                lastJumpRequestTime = System.currentTimeMillis()
                            }
                            false 
                        }
                    }
                }
            }
        }

        // ==========================================
        //  功能 1: 強力防捲動 (Anti-Scroll)
        // ==========================================
        
        // 定義攔截邏輯
        // 參數說明： checkRecycler: 是否要檢查 RecyclerView 的 ID (如果是 Hook LayoutManager 則很難檢查，設為 false)
        fun shouldBlock(obj: Any, checkRecycler: Boolean): Boolean {
            // 1. 檢查是否有通行證 (按下了按鈕)
            if ((System.currentTimeMillis() - lastJumpRequestTime) < 2000) {
                return false // 有通行證，不攔截
            }

            // 2. 檢查目標是否為聊天室 (如果 checkRecycler 為 true)
            if (checkRecycler && chatListRecyclerId != 0 && obj is RecyclerView) {
                if (obj.id != chatListRecyclerId) {
                    return false // 不是聊天室列表，不攔截
                }
            }
            
            // 3. 如果是 LayoutManager，我們假設它是在聊天室環境下被呼叫的
            // (因為很難從 LayoutManager 反推回 RecyclerView ID，除非用反射，太慢)
            // 為了避免誤殺，這裡其實是「寧可錯殺一千(其他列表可能也無法程式化捲動)，不可放過一個(聊天室自動捲動)」
            // 但因為手動滑動不受 scrollToPosition 影響，所以副作用很小。
            
            return true // 攔截！
        }

        // --- Hook RecyclerView 的方法 ---

        patcher.before<RecyclerView>("scrollToPosition", Int::class.javaPrimitiveType!!) {
            if (shouldBlock(it.thisObject, true)) it.result = null
        }

        patcher.before<RecyclerView>("smoothScrollToPosition", Int::class.javaPrimitiveType!!) {
            if (shouldBlock(it.thisObject, true)) it.result = null
        }

        // --- Hook LinearLayoutManager 的方法 (這是你之前可能漏掉的關鍵) ---
        // Discord 很喜歡直接叫用 LayoutManager 來做精確定位

        patcher.before<LinearLayoutManager>(
            "scrollToPosition", 
            Int::class.javaPrimitiveType!!
        ) {
            // 對於 LayoutManager，我們無法輕易檢查 ID，直接依賴通行證機制
            if (shouldBlock(it.thisObject, false)) it.result = null
        }

        patcher.before<LinearLayoutManager>(
            "smoothScrollToPosition", 
            RecyclerView::class.java, 
            RecyclerView.State::class.java, 
            Int::class.javaPrimitiveType!!
        ) {
            if (shouldBlock(it.thisObject, false)) it.result = null
        }

        patcher.before<LinearLayoutManager>(
            "scrollToPositionWithOffset",
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!
        ) { 
            if (shouldBlock(it.thisObject, false)) it.result = null
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
