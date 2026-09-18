package com.pixelforge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.pixelforge.ui.screens.HomeScreen
import com.pixelforge.ui.screens.WorkspaceScreen
import com.pixelforge.ui.theme.PixelForgeTheme
import com.pixelforge.ui.viewmodel.ProjectViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: ProjectViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PixelForgeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PixelForgeApp(viewModel)
                }
            }
        }
    }
}

@Composable
fun PixelForgeApp(viewModel: ProjectViewModel) {
    val navController: NavHostController = rememberNavController()

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(viewModel = viewModel, onOpenProject = { projectId ->
                viewModel.openProject(projectId) {
                    navController.navigate("workspace")
                }
            })
        }
        composable("workspace") {
            WorkspaceScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
    }
}
