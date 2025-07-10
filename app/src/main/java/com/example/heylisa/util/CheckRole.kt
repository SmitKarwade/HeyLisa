package com.example.heylisa.util

import android.app.role.RoleManager
import android.content.Context

object CheckRole{
    fun isDefaultAssistant(context: Context): Boolean {
        val roleManager = context.getSystemService(RoleManager::class.java)
        return roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
    }
}
