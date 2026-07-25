package com.tcs.vehicleassistant

import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

class ChatAdapter(private val messages: MutableList<ChatMessage>) : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    class ChatViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val root: LinearLayout = view.findViewById(R.id.chatRoot)
        val card: MaterialCardView = view.findViewById(R.id.bubbleCard)
        val messageText: TextView = view.findViewById(R.id.messageText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_message, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val msg = messages[position]
        holder.messageText.text = msg.text

        if (msg.isUser) {
            holder.root.gravity = Gravity.END
            holder.card.setCardBackgroundColor(Color.parseColor("#BB86FC"))
            holder.messageText.setTextColor(Color.BLACK)
        } else {
            holder.root.gravity = Gravity.START
            holder.card.setCardBackgroundColor(Color.parseColor("#2A2A2A"))
            holder.messageText.setTextColor(Color.WHITE)
        }
    }

    override fun getItemCount() = messages.size

    fun addMessage(msg: ChatMessage) {
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
    }
    
    fun replaceLastMessage(fullText: String) {
        if (messages.isNotEmpty()) {
            messages.last().let {
                messages[messages.size - 1] = it.copy(text = fullText)
                notifyItemChanged(messages.size - 1)
            }
        }
    }
    
    fun updateLastMessage(text: String) {
        if (messages.isNotEmpty()) {
            messages.last().let {
                messages[messages.size - 1] = it.copy(text = it.text + text)
                notifyItemChanged(messages.size - 1)
            }
        }
    }
    
    fun clearMessages() {
        messages.clear()
        notifyDataSetChanged()
    }
    
    fun getLastMessageText(): String? {
        return messages.lastOrNull()?.text
    }
}
