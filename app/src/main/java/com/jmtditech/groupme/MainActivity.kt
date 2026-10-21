package com.jmtditech.groupme

import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.jmtditech.groupme.data.AppState
import com.jmtditech.groupme.ui.AppTheme
import com.jmtditech.groupme.ui.AddContactScreen
import com.jmtditech.groupme.ui.ChatScreen
import com.jmtditech.groupme.ui.ConversationListScreen
import com.jmtditech.groupme.ui.LoginScreen
import com.jmtditech.groupme.ui.MainViewModel
import com.jmtditech.groupme.ui.NewChatScreen
import com.jmtditech.groupme.ui.NewGroupScreen
import com.jmtditech.groupme.ui.SearchScreen
import com.jmtditech.groupme.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) vm.handleIntent(intent)
        setContent { AppTheme { AppRoot(vm) } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        vm.handleIntent(intent)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (AppState.keyInterceptor?.invoke(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        AppState.foreground = true
    }

    override fun onStop() {
        super.onStop()
        AppState.foreground = false
    }
}

@Composable
private fun AppRoot(vm: MainViewModel) {
    val signedIn by vm.signedIn.collectAsStateWithLifecycle()
    when (signedIn) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        false -> LoginScreen(vm)
        true -> MainNav(vm)
    }
}

@Composable
private fun MainNav(vm: MainViewModel) {
    val nav = rememberNavController()
    val pending by vm.pendingConv.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    val serviceOn by vm.serviceEnabled.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.onSignedIn() }
    LaunchedEffect(serviceOn) {
        if (!serviceOn) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.runForegroundPush() }
        }
    }
    LaunchedEffect(pending) {
        pending?.let {
            nav.navigate("chat/" + Uri.encode(it)) { launchSingleTop = true }
            vm.pendingConv.value = null
        }
    }

    NavHost(navController = nav, startDestination = "list") {
        composable("list") {
            ConversationListScreen(
                vm = vm,
                onOpen = { nav.navigate("chat/" + Uri.encode(it)) },
                onSearch = { nav.navigate("search") },
                onSettings = { nav.navigate("settings") },
                onNew = { nav.navigate("new") },
            )
        }
        composable("new") {
            NewChatScreen(
                onBack = { nav.popBackStack() },
                onNewGroup = { nav.navigate("newgroup") },
                onAddContact = { nav.navigate("addcontact") },
                // Saved phone/email contacts can't be messaged directly, so start a group with them selected.
                onInvite = { key -> nav.navigate("newgroup?with=" + Uri.encode(key)) },
                // Replace the "new chat" screens with the chat so Back returns to the list.
                onOpenChat = { key -> nav.navigate("chat/" + Uri.encode(key)) { popUpTo("list") } },
            )
        }
        composable("addcontact") {
            AddContactScreen(onBack = { nav.popBackStack() })
        }
        composable(
            route = "newgroup?with={with}",
            arguments = listOf(
                navArgument("with") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            NewGroupScreen(
                preselect = entry.arguments?.getString("with"),
                onBack = { nav.popBackStack() },
                onOpenChat = { key -> nav.navigate("chat/" + Uri.encode(key)) { popUpTo("list") } },
            )
        }
        composable(
            route = "chat/{key}",
            arguments = listOf(navArgument("key") { type = NavType.StringType }),
        ) { entry ->
            val key = entry.arguments?.getString("key") ?: return@composable
            ChatScreen(convKey = key, onBack = { nav.popBackStack() })
        }
        composable("search") {
            SearchScreen(
                onOpen = { nav.navigate("chat/" + Uri.encode(it)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable("settings") {
            SettingsScreen(vm = vm, onBack = { nav.popBackStack() })
        }
    }
}
