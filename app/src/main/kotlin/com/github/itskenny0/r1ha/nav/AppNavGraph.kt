package com.github.itskenny0.r1ha.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import com.github.itskenny0.r1ha.feature.about.AboutScreen
import com.github.itskenny0.r1ha.feature.cardstack.CardStackScreen
import com.github.itskenny0.r1ha.feature.favoritespicker.FavoritesPickerScreen
import com.github.itskenny0.r1ha.feature.onboarding.OnboardingScreen
import com.github.itskenny0.r1ha.feature.settings.SettingsScreen
import com.github.itskenny0.r1ha.feature.themepicker.ThemePickerScreen

@Composable
fun AppNavGraph(
    navController: NavHostController,
    startDestination: String,
    haRepository: HaRepository,
    settings: SettingsRepository,
    tokens: TokenStore,
    wheelInput: WheelInput,
) {
    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                settings = settings,
                tokens = tokens,
                onComplete = {
                    navController.navigate(Routes.CARD_STACK) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.CARD_STACK) {
            CardStackScreen(
                haRepository = haRepository,
                settings = settings,
                wheelInput = wheelInput,
                // launchSingleTop = true on every push so a rapid double-tap on the gear or a
                // double-fire of the swipe gesture can't stack two copies of the same screen
                // on the back stack (which would otherwise need two back-presses to escape).
                onOpenFavoritesPicker = {
                    navController.navigate(Routes.FAVORITES_PICKER) { launchSingleTop = true }
                },
                onOpenSettings = {
                    navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                },
            )
        }
        composable(Routes.FAVORITES_PICKER) {
            FavoritesPickerScreen(
                haRepository = haRepository,
                settings = settings,
                wheelInput = wheelInput,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                settings = settings,
                tokens = tokens,
                haRepository = haRepository,
                wheelInput = wheelInput,
                onOpenThemePicker = {
                    navController.navigate(Routes.THEME_PICKER) { launchSingleTop = true }
                },
                onOpenAbout = {
                    navController.navigate(Routes.ABOUT) { launchSingleTop = true }
                },
                onOpenAssist = {
                    navController.navigate(Routes.ASSIST) { launchSingleTop = true }
                },
                onOpenScenes = {
                    navController.navigate(Routes.SCENES) { launchSingleTop = true }
                },
                onOpenLogbook = {
                    navController.navigate(Routes.LOGBOOK) { launchSingleTop = true }
                },
                onOpenTemplate = {
                    navController.navigate(Routes.TEMPLATE) { launchSingleTop = true }
                },
                onOpenServiceCaller = {
                    navController.navigate(Routes.SERVICE_CALLER) { launchSingleTop = true }
                },
                onOpenNotifications = {
                    navController.navigate(Routes.NOTIFICATIONS) { launchSingleTop = true }
                },
                onOpenCameras = {
                    navController.navigate(Routes.CAMERAS) { launchSingleTop = true }
                },
                onOpenWeather = {
                    navController.navigate(Routes.WEATHER) { launchSingleTop = true }
                },
                onOpenPersons = {
                    navController.navigate(Routes.PERSONS) { launchSingleTop = true }
                },
                onOpenCalendars = {
                    navController.navigate(Routes.CALENDARS) { launchSingleTop = true }
                },
                onOpenLongLivedToken = {
                    navController.navigate(Routes.LONG_LIVED_TOKEN) { launchSingleTop = true }
                },
                onOpenSystemHealth = {
                    navController.navigate(Routes.SYSTEM_HEALTH) { launchSingleTop = true }
                },
                onSignedOut = {
                    // Clear the whole back stack so a stale CardStack/Onboarding can't be
                    // popped back to; then land fresh on Onboarding.
                    navController.navigate(Routes.ONBOARDING) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.THEME_PICKER) {
            ThemePickerScreen(
                settings = settings,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen(
                haRepository = haRepository,
                settings = settings,
                wheelInput = wheelInput,
                onOpenDevMenu = {
                    navController.navigate(Routes.DEV_MENU) { launchSingleTop = true }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.DEV_MENU) {
            com.github.itskenny0.r1ha.feature.devmenu.DevMenuScreen(
                settings = settings,
                tokens = tokens,
                wheelInput = wheelInput,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.ASSIST) {
            com.github.itskenny0.r1ha.feature.assist.AssistScreen(
                haRepository = haRepository,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SCENES) {
            com.github.itskenny0.r1ha.feature.scenes.ScenesScreen(
                haRepository = haRepository,
                settings = settings,
                wheelInput = wheelInput,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.LOGBOOK) {
            com.github.itskenny0.r1ha.feature.logbook.LogbookScreen(
                haRepository = haRepository,
                settings = settings,
                wheelInput = wheelInput,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.TEMPLATE) {
            com.github.itskenny0.r1ha.feature.template.TemplateScreen(
                haRepository = haRepository,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SERVICE_CALLER) {
            com.github.itskenny0.r1ha.feature.service.ServiceCallerScreen(
                haRepository = haRepository,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.NOTIFICATIONS) {
            com.github.itskenny0.r1ha.feature.notifications.NotificationsScreen(
                haRepository = haRepository,
                settings = settings,
                wheelInput = wheelInput,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.CAMERAS) {
            com.github.itskenny0.r1ha.feature.cameras.CamerasScreen(
                haRepository = haRepository,
                settings = settings,
                tokens = tokens,
                wheelInput = wheelInput,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.WEATHER) {
            com.github.itskenny0.r1ha.feature.weather.WeatherScreen(
                haRepository = haRepository,
                settings = settings,
                wheelInput = wheelInput,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.PERSONS) {
            com.github.itskenny0.r1ha.feature.persons.PersonsScreen(
                haRepository = haRepository,
                settings = settings,
                wheelInput = wheelInput,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.CALENDARS) {
            com.github.itskenny0.r1ha.feature.calendars.CalendarsScreen(
                haRepository = haRepository,
                settings = settings,
                wheelInput = wheelInput,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.LONG_LIVED_TOKEN) {
            com.github.itskenny0.r1ha.feature.longlived.LongLivedTokenScreen(
                settings = settings,
                tokens = tokens,
                haRepository = haRepository,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SYSTEM_HEALTH) {
            com.github.itskenny0.r1ha.feature.systemhealth.SystemHealthScreen(
                haRepository = haRepository,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
