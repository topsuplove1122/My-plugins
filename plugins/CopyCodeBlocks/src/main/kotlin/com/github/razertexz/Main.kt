package com.github.razertexz

import android.content.Context
import android.widget.ImageView
import android.view.View
import android.view.MotionEvent
import android.graphics.Color
import android.view.ViewGroup
import android.os.Bundle

import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.constraintlayout.widget.ConstraintLayout
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
    
    private var lastJumpRequestTime = 0L
    private var chatRecyclerId = 0

    override fun start(ctx: Context) {
        
        chatRecyclerId = Utils.getResId("chat_list_recycler_view", "id")

        // ==========================================
        //  功能 0: 監聽「跳到底部」按鈕
        // ==========================================
        patcher.after<Fragment>("onViewCreated", View::class.java, Bundle::class.java) {
            // 寬鬆檢查，只要名稱包含 WidgetChatList 就算
            if (it.thisObject::class.java.name.contains("WidgetChatList")) {
                val view = it.args[0] as View
                
                // 1. 嘗試一開始就強制關閉吸附
                try {
                    if (chatRecyclerId != 0) {
                        val recycler = view.findViewById<RecyclerView>(chatRecyclerId)
                        val manager = recycler?.layoutManager as? LinearLayoutManager
                        manager?.stackFromEnd = false
                    }
                } catch (e: Exception) {}

                // 2. 綁定按鈕
                val scrollBtnId = Utils.getResId("chat_list_scroll_to_bottom", "id")
                if (scrollBtnId != 0) {
                    val scrollBtn = view.findViewById<View>(scrollBtnId)
                    scrollBtn?.setOnTouchListener { _, event ->
                        if (event.action == MotionEvent.ACTION_DOWN) {
                            // 發放通行證
                            lastJumpRequestTime = System.currentTimeMillis()
                            
                            // 手動觸發跳轉
                            try {
                                val recycler = view.findViewById<RecyclerView>(chatRecyclerId)
                                val manager = recycler?.layoutManager as? LinearLayoutManager
                                if (manager != null) {
                                    // 暫時開啟吸附，這樣才能跳到底
                                    manager.stackFromEnd = true 
                                    manager.scrollToPosition(manager.itemCount - 1)
                                }
                            } catch (e: Exception) {}
                        }
                        false 
                    }
                }
            }
        }

        // ==========================================
        //  功能 1: 參數竄改與攔截 (Before Hooks)
        // ==========================================

        fun shouldBlock(obj: Any): Boolean {
            // 1. 檢查通行證 (2秒內)
            if ((System.currentTimeMillis() - lastJumpRequestTime) < 2000) {
                return false // 有通行證，放行
            }
            
            // 2. 檢查是不是聊天室
            // 為了防止誤殺，如果沒有找到 ID，我們稍微保守一點
            if (chatRecyclerId != 0 && obj is RecyclerView) {
                if (obj.id == chatRecyclerId) return true
            }
            
            // 3. LayoutManager 很難判斷 ID，所以我們採取「寧可錯殺」策略
            if (obj is LinearLayoutManager) {
                return true
            }

            return false
        }

        // --- 1. 綁架 setStackFromEnd (竄改參數) ---
        // 當 Discord 想設為 True 時，我們偷偷改成 False
        patcher.before<LinearLayoutManager>(
            "setStackFromEnd", 
            Boolean::class.javaPrimitiveType!!
        ) {
            if (shouldBlock(it.thisObject)) {
                // 【關鍵】直接修改參數
                // 原本是 true，我們改成 false，然後讓方法繼續執行
                // 這樣 Discord 就不會報錯，但效果被我們改掉了
                it.args[0] = false
            }
        }

        // --- 2. 攔截 scrollToPosition (取消執行) ---
        // 在 Aliucord 的 before hook 中，設定 result = null 會跳過原方法執行
        patcher.before<RecyclerView>(
            "scrollToPosition", 
            Int::class.javaPrimitiveType!!
        ) {
            if (shouldBlock(it.thisObject)) {
                it.result = null // 攔截！不執行！
            }
        }

        patcher.before<RecyclerView>(
            "smoothScrollToPosition", 
            Int::class.javaPrimitiveType!!
        ) {
            if (shouldBlock(it.thisObject)) {
                it.result = null
            }
        }
        
        // --- 3. 攔截 LayoutManager 的捲動 ---
        
        patcher.before<LinearLayoutManager>(
            "scrollToPositionWithOffset",
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!
        ) {
            if (shouldBlock(it.thisObject)) {
                it.result = null
            }
        }
        
        patcher.before<LinearLayoutManager>(
            "scrollToPosition",
            Int::class.javaPrimitiveType!!
        ) {
             if (shouldBlock(it.thisObject)) {
                 it.result = null
             }
        }
        
        patcher.before<LinearLayoutManager>(
            "smoothScrollToPosition",
            RecyclerView::class.java,
            RecyclerView.State::class.java,
            Int::class.javaPrimitiveType!!
        ) {
             if (shouldBlock(it.thisObject)) {
                 it.result = null
             }
        }


        // ==========================================
        //  功能 2: 複製按鈕 (保持不變)
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
