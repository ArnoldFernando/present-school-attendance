package com.attendancefr.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.attendancefr.ui.screens.attendance.AttendanceScreen
import com.attendancefr.ui.screens.enroll.EnrollScreen
import com.attendancefr.ui.screens.exports.ExportsScreen
import com.attendancefr.ui.screens.manual.ManualOverrideScreen
import com.attendancefr.ui.screens.reports.ReportsScreen
import com.attendancefr.ui.screens.settings.SettingsScreen
import com.attendancefr.ui.screens.students.StudentEditScreen
import com.attendancefr.ui.screens.students.StudentsScreen

sealed class Dest(val route: String, val label: String, val icon: ImageVector? = null) {
    data object Students : Dest("students", "Students", Icons.Outlined.People)
    data object Attendance : Dest("attendance", "Take", Icons.Outlined.Face)
    data object Reports : Dest("reports", "Reports", Icons.Outlined.BarChart)
    data object Settings : Dest("settings", "Settings", Icons.Outlined.Settings)
    data object Exports : Dest("exports", "Exported Files", Icons.Outlined.Description)
    data object Enroll : Dest("enroll?studentId={studentId}", "Enroll") {
        fun create(studentId: Long? = null): String =
            if (studentId == null) "enroll" else "enroll?studentId=$studentId"
    }
    data object StudentEdit : Dest("student/{id}", "Edit") {
        fun create(id: Long) = "student/$id"
    }
    data object Manual : Dest("manual?className={className}", "Manual") {
        fun create(className: String = "") =
            "manual?className=${android.net.Uri.encode(className)}"
    }
}

private val tabs = listOf(Dest.Students, Dest.Attendance, Dest.Reports, Dest.Settings)

@Composable
fun AttendanceNavHost() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route.orEmpty()
    val showBar = tabs.any { current == it.route || current.startsWith(it.route) }

    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    tabs.forEach { dest ->
                        NavigationBarItem(
                            selected = current == dest.route,
                            onClick = {
                                nav.navigate(dest.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { dest.icon?.let { Icon(it, contentDescription = dest.label) } },
                            label = { Text(dest.label) },
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Dest.Students.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Dest.Students.route) {
                StudentsScreen(
                    onAdd = { nav.navigate("enroll") },
                    onEdit = { id -> nav.navigate(Dest.StudentEdit.create(id)) },
                    onReEnroll = { id -> nav.navigate(Dest.Enroll.create(id)) },
                )
            }
            composable(Dest.Attendance.route) {
                AttendanceScreen(
                    onManual = { className -> nav.navigate(Dest.Manual.create(className)) },
                )
            }
            composable(Dest.Reports.route) {
                ReportsScreen(
                    onNavigateToExports = { nav.navigate(Dest.Exports.route) },
                )
            }
            composable(Dest.Settings.route) { SettingsScreen() }
            composable(Dest.Exports.route) {
                ExportsScreen(onBack = { nav.popBackStack() })
            }
            composable(
                route = "enroll?studentId={studentId}",
                arguments = listOf(
                    navArgument("studentId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                ),
            ) {
                EnrollScreen(onDone = { nav.popBackStack() })
            }
            composable(
                route = "student/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) {
                StudentEditScreen(
                    onDone = { nav.popBackStack() },
                    onReEnroll = { id ->
                        nav.popBackStack()
                        nav.navigate(Dest.Enroll.create(id))
                    },
                )
            }
            composable(
                route = "manual?className={className}",
                arguments = listOf(
                    navArgument("className") {
                        type = NavType.StringType
                        defaultValue = ""
                    }
                ),
            ) {
                ManualOverrideScreen(onDone = { nav.popBackStack() })
            }
        }
    }
}