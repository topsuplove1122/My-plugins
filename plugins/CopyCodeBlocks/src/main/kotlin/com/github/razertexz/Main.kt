package com.github.razertexz

import androidx.constraintlayout.widget.ConstraintLayout
import android.content.Context
import android.widget.ImageView
import android.view.View
import android.graphics.Color // 引入顏色庫

import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.*
import com.aliucord.Utils
import com.aliucord.utils.DimenUtils

import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.MessageEntry
import com.discord.utilities.view.text.SimpleDraweeSpanTextView

import com.lytefast.flexinput.R
import androidx.recyclerview.widget.RecyclerView

@AliucordPlugin(requiresRestart = true)
class Main : Plugin() {
    private val COPY_BTN_ID = 999888777 // 隨意設定一個 ID

    override fun start(ctx: Context) {
        // 加大一點按鈕尺寸，讓它更明顯
        val copyBtnSize = DimenUtils.defaultPadding + 10 
        val copyBtnMargin = DimenUtils.defaultPadding / 4

        val copyIcon = ctx.getDrawable(R.e.ic_copy_24dp)!!.mutate()
        
        // 【設定顏色】：這裡設為亮青色 (Cyan)，在深色模式下超級明顯
        // 如果想要紅色，改成 Color.RED；想要白色，改成 Color.WHITE
        copyIcon.setTint(Color.CYAN) 

        patcher.after<WidgetChatListAdapterItemMessage>("processMessageText", SimpleDraweeSpanTextView::class.java, MessageEntry::class.java) {
            val textView = it.args[0] as SimpleDraweeSpanTextView
            val messageEntry = it.args[1] as MessageEntry
            val holder = it.thisObject as RecyclerView.ViewHolder
            val view = holder.itemView

            var copyBtn = root.findViewById<ImageView>(COPY_BTN_ID)

            if (copyBtn == null) {
                copyBtn = ImageView(root.context).apply {
                    id = COPY_BTN_ID
                    setImageDrawable(copyIcon)
                    
                    // 【關鍵修改】：移除了 alpha 設定，現在是 100% 不透明
                    // alpha = 0.6f  <-- 這行刪掉了
                    
                    // 設定背景色 (可選)：如果你想要按鈕後面有個黑色底框，把下面這行註解打開
                    // setBackgroundColor(Color.parseColor("#88000000")) 

                    layoutParams = ConstraintLayout.LayoutParams(copyBtnSize, copyBtnSize).apply {
                        topToTop = textView.id
                        endToEnd = textView.id
                        topMargin = copyBtnMargin
                        rightMargin = copyBtnMargin
                    }
                    
                    // 增加一點 padding 讓圖示在按鈕框框內置中，不會貼邊
                    setPadding(5, 5, 5, 5)
                }
                root.addView(copyBtn)
            }

            copyBtn.visibility = View.VISIBLE

            copyBtn.setOnClickListener {
                val content = messageEntry.message.content
                Utils.setClipboard(content, content)
                Utils.showToast("已複製！")
            }
        }
    }

    override fun stop(ctx: Context) = patcher.unpatchAll()
}
