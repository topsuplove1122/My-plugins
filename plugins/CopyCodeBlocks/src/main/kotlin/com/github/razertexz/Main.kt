package com.github.razertexz

import android.content.Context
import android.widget.ImageView
import android.view.View
import android.view.MotionEvent
import android.graphics.Color
import android.os.Bundle

// 引入 RecyclerView 相關類別
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
    
    // 標記：是否正在執行手動跳轉
    private var isManualJumping = false
    
    // 用來儲存被我們替換掉的 RecyclerView，方便後續存取
    private var chatRecycler: RecyclerView? = null

    override fun start(ctx: Context) {
        
        // ==========================================
        //  功能 1: 替換 LayoutManager (特洛伊木馬)
        // ==========================================
        
        patcher.after<Fragment>("onViewCreated", View::class.java, Bundle::class.java) {
            if (it.thisObject::class.java.name.contains("WidgetChatList")) {
                val view = it.args[0] as View
                val recyclerId = Utils.getResId("chat_list_recycler_view", "id")
                
                if (recyclerId != 0) {
                    val recycler = view.findViewById<RecyclerView>(recyclerId)
                    if (recycler != null) {
                        chatRecycler = recycler
                        
                        // 1. 取得原本的 LayoutManager 參數
                        val oldManager = recycler.layoutManager as? LinearLayoutManager
                        val orientation = oldManager?.orientation ?: LinearLayoutManager.VERTICAL
                        val reverseLayout = oldManager?.reverseLayout ?: false
                        
                        // 2. 建立我們的「特洛伊木馬」Manager
                        // 這是繼承自 LinearLayoutManager 的匿名類別
                        val safeManager = object : LinearLayoutManager(view.context, orientation, reverseLayout) {
                            
                            // 覆寫：平滑捲動
                            override fun smoothScrollToPosition(recyclerView: RecyclerView?, state: RecyclerView.State?, position: Int) {
                                if (isManualJumping) {
                                    super.smoothScrollToPosition(recyclerView, state, position)
                                } else {
                                    // 什麼都不做，直接忽略 Discord 的捲動請求
                                }
                            }

                            // 覆寫：瞬間捲動
                            override fun scrollToPosition(position: Int) {
                                if (isManualJumping) {
                                    super.scrollToPosition(position)
                                } else {
                                    // 忽略
                                }
                            }

                            // 覆寫：帶偏移量的捲動
                            override fun scrollToPositionWithOffset(position: Int, offset: Int) {
                                if (isManualJumping) {
                                    super.scrollToPositionWithOffset(position, offset)
                                } else {
                                    // 忽略
                                }
                            }

                            // 覆寫：自動置底設定
                            override fun setStackFromEnd(stackFromEnd: Boolean) {
                                if (isManualJumping) {
                                    super.setStackFromEnd(stackFromEnd)
                                } else {
                                    // 強制設為 false，不管 Discord 傳什麼進來
                                    super.setStackFromEnd(false)
                                }
                            }
                            
                            // 當佈局完成時，確保不會自動開啟
                            override fun onLayoutChildren(recycler: RecyclerView.Recycler?, state: RecyclerView.State?) {
                                if (!isManualJumping) {
                                    this.stackFromEnd = false
                                }
                                super.onLayoutChildren(recycler, state)
                            }
                        }
                        
                        // 3. 把特洛伊木馬安裝上去
                        safeManager.stackFromEnd = false // 預設關閉
                        recycler.layoutManager = safeManager
                    }
                }

                // 4. 綁定按鈕 (手動跳轉邏輯)
                val scrollBtnId = Utils.getResId("chat_list_scroll_to_bottom", "id")
                if (scrollBtnId != 0) {
                    val scrollBtn = view.findViewById<View>(scrollBtnId)
                    scrollBtn?.setOnTouchListener { _, event ->
                        if (event.action == MotionEvent.ACTION_DOWN) {
                            // 開啟綠燈
                            isManualJumping = true
                            
                            // 拿到我們的木馬 Manager
                            val manager = chatRecycler?.layoutManager as? LinearLayoutManager
                            if (manager != null) {
                                // 因為是在內部判斷 isManualJumping，所以這裡呼叫 super 的方法會生效
                                manager.stackFromEnd = true 
                                
                                // 強制捲動
                                val count = manager.itemCount
                                if (count > 0) {
                                    manager.scrollToPosition(count - 1)
                                }
                                
                                // 1秒後自動關閉綠燈
                                Utils.mainThread.postDelayed({
                                    isManualJumping = false
                                    manager.stackFromEnd = false
                                }, 1000)
                            }
                        }
                        false 
                    }
                }
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
