package com.mutqan.hadith.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mutqan.hadith.di.AppContainer
import com.mutqan.hadith.ui.analytics.AnalyticsRoute
import com.mutqan.hadith.ui.analytics.AnalyticsViewModel
import com.mutqan.hadith.ui.analyticsViewModelFactory
import com.mutqan.hadith.ui.details.HadithDetailsRoute
import com.mutqan.hadith.ui.details.HadithDetailsViewModel
import com.mutqan.hadith.ui.graph.NarratorGraphRoute
import com.mutqan.hadith.ui.graph.NarratorGraphViewModel
import com.mutqan.hadith.ui.hadithDetailsViewModelFactory
import com.mutqan.hadith.ui.hadithStudyViewModelFactory
import com.mutqan.hadith.ui.home.HomeActions
import com.mutqan.hadith.ui.home.HomeRoute
import com.mutqan.hadith.ui.home.HomeViewModel
import com.mutqan.hadith.ui.homeViewModelFactory
import com.mutqan.hadith.ui.insights.InsightsActions
import com.mutqan.hadith.ui.insights.InsightsRoute
import com.mutqan.hadith.ui.insights.InsightsViewModel
import com.mutqan.hadith.ui.insightsViewModelFactory
import com.mutqan.hadith.ui.library.LibraryRoute
import com.mutqan.hadith.ui.library.LibraryViewModel
import com.mutqan.hadith.ui.libraryViewModelFactory
import com.mutqan.hadith.ui.narratorGraphViewModelFactory
import com.mutqan.hadith.ui.missinglink.MissingLinkRoute
import com.mutqan.hadith.ui.missinglink.MissingLinkViewModel
import com.mutqan.hadith.ui.missingLinkViewModelFactory
import com.mutqan.hadith.ui.review.ReviewMode
import com.mutqan.hadith.ui.review.ReviewRoute
import com.mutqan.hadith.ui.review.ReviewViewModel
import com.mutqan.hadith.ui.reviewViewModelFactory
import com.mutqan.hadith.ui.sanadBuilderViewModelFactory
import com.mutqan.hadith.ui.sanadbuilder.SanadBuilderRoute
import com.mutqan.hadith.ui.sanadbuilder.SanadBuilderViewModel
import com.mutqan.hadith.ui.settings.SettingsRoute
import com.mutqan.hadith.ui.settings.SettingsViewModel
import com.mutqan.hadith.ui.settingsViewModelFactory
import com.mutqan.hadith.ui.study.HadithStudyRoute
import com.mutqan.hadith.ui.study.HadithStudyViewModel

object Routes {
    const val HOME = "home"

    const val ARG_MODE = "mode"
    const val ARG_ID = "id"
    const val ARG_HADITH_ID = "hadithId"
    const val NO_ID = -1L

    /** [ARG_ID] is the Hadith for a single review and the narrator for a narrator review. */
    const val REVIEW = "review/{$ARG_MODE}?$ARG_ID={$ARG_ID}"
    const val SANAD_BUILDER = "sanad_builder/{$ARG_HADITH_ID}"
    const val MISSING_LINK = "missing_link/{$ARG_HADITH_ID}"
    const val NARRATOR_GRAPH = "narrator_graph"
    const val ANALYTICS = "analytics"
    const val LIBRARY = "library"
    const val INSIGHTS = "insights"
    const val SETTINGS = "settings"
    const val HADITH_DETAILS = "hadith_details/{$ARG_HADITH_ID}"
    const val HADITH_STUDY = "hadith_study/{$ARG_HADITH_ID}"

    fun review(mode: ReviewMode, id: Long? = null): String =
        "review/${mode.route}" + if (id != null) "?$ARG_ID=$id" else ""

    fun sanadBuilder(hadithId: Long): String = "sanad_builder/$hadithId"

    fun missingLink(hadithId: Long): String = "missing_link/$hadithId"

    fun hadithDetails(hadithId: Long): String = "hadith_details/$hadithId"

    fun hadithStudy(hadithId: Long): String = "hadith_study/$hadithId"
}

private const val TRANSITION_MS = 300

/**
 * The app is right-to-left, so "forward" slides toward the start edge; using the Start/End
 * directions (not Left/Right) keeps the motion natural in either layout direction.
 */
private val forwardIn: AnimatedContentTransitionScope<NavBackStackEntry>.() -> androidx.compose.animation.EnterTransition = {
    slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(TRANSITION_MS)) + fadeIn(tween(TRANSITION_MS))
}
private val forwardOut: AnimatedContentTransitionScope<NavBackStackEntry>.() -> androidx.compose.animation.ExitTransition = {
    slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(TRANSITION_MS), targetOffset = { it / 4 }) +
        fadeOut(tween(TRANSITION_MS))
}
private val backIn: AnimatedContentTransitionScope<NavBackStackEntry>.() -> androidx.compose.animation.EnterTransition = {
    slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(TRANSITION_MS), initialOffset = { it / 4 }) +
        fadeIn(tween(TRANSITION_MS))
}
private val backOut: AnimatedContentTransitionScope<NavBackStackEntry>.() -> androidx.compose.animation.ExitTransition = {
    slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(TRANSITION_MS)) + fadeOut(tween(TRANSITION_MS))
}

/** Moving between two tabs is a change of view, not a step forward or back, so it fades instead of sliding. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch(): Boolean =
    TopLevelTab.isTopLevel(initialState.destination.route) && TopLevelTab.isTopLevel(targetState.destination.route)

/**
 * The root of the app: the bottom bar over the destinations. The bar is there on the three tabs and gives way
 * to the screens pushed above them, which are tasks of their own (a review, a reading) with their own Back.
 */
@Composable
fun MutqanNavHost(
    container: AppContainer,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        // Each screen below handles the system bars itself; only the space of the bottom bar is taken here.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            AnimatedVisibility(
                visible = currentRoute == null || TopLevelTab.isTopLevel(currentRoute),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                MutqanBottomBar(currentRoute, onSelect = navController::navigateToTab)
            }
        },
    ) { padding ->
        MutqanDestinations(container, navController, Modifier.padding(padding).consumeWindowInsets(padding))
    }
}

@Composable
private fun MutqanDestinations(container: AppContainer, navController: NavHostController, modifier: Modifier) {
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier,
        enterTransition = { if (isTabSwitch()) fadeIn(tween(TRANSITION_MS)) else forwardIn(this) },
        exitTransition = { if (isTabSwitch()) fadeOut(tween(TRANSITION_MS)) else forwardOut(this) },
        popEnterTransition = { if (isTabSwitch()) fadeIn(tween(TRANSITION_MS)) else backIn(this) },
        popExitTransition = { if (isTabSwitch()) fadeOut(tween(TRANSITION_MS)) else backOut(this) },
    ) {
        composable(Routes.HOME) {
            val viewModel: HomeViewModel = viewModel(factory = container.homeViewModelFactory())
            HomeRoute(
                viewModel = viewModel,
                actions = HomeActions(
                    onStartDueReview = { navController.navigate(Routes.review(ReviewMode.DUE)) },
                    onStudyHadith = { navController.navigate(Routes.hadithStudy(it)) },
                    onSanadBuilder = { navController.navigate(Routes.sanadBuilder(it)) },
                    onMissingLink = { navController.navigate(Routes.missingLink(it)) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                ),
            )
        }

        composable(Routes.INSIGHTS) {
            val viewModel: InsightsViewModel = viewModel(factory = container.insightsViewModelFactory())
            InsightsRoute(
                viewModel = viewModel,
                actions = InsightsActions(
                    onOpenGraph = { navController.navigate(Routes.NARRATOR_GRAPH) },
                    onOpenAnalytics = { navController.navigate(Routes.ANALYTICS) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                ),
            )
        }

        composable(
            route = Routes.REVIEW,
            arguments = listOf(
                navArgument(Routes.ARG_MODE) { type = NavType.StringType },
                navArgument(Routes.ARG_ID) { type = NavType.LongType; defaultValue = Routes.NO_ID },
            ),
        ) { entry ->
            val mode = ReviewMode.fromRoute(entry.arguments?.getString(Routes.ARG_MODE))
            val targetId = entry.arguments?.getLong(Routes.ARG_ID)?.takeIf { it != Routes.NO_ID }
            val viewModel: ReviewViewModel = viewModel(factory = container.reviewViewModelFactory(mode, targetId))
            ReviewRoute(viewModel, mode, onClose = { navController.popBackStack() })
        }

        composable(
            route = Routes.SANAD_BUILDER,
            arguments = listOf(navArgument(Routes.ARG_HADITH_ID) { type = NavType.LongType }),
        ) { entry ->
            val hadithId = entry.arguments?.getLong(Routes.ARG_HADITH_ID) ?: return@composable
            val viewModel: SanadBuilderViewModel = viewModel(factory = container.sanadBuilderViewModelFactory(hadithId))
            SanadBuilderRoute(viewModel, onClose = { navController.popBackStack() })
        }

        composable(
            route = Routes.MISSING_LINK,
            arguments = listOf(navArgument(Routes.ARG_HADITH_ID) { type = NavType.LongType }),
        ) { entry ->
            val hadithId = entry.arguments?.getLong(Routes.ARG_HADITH_ID) ?: return@composable
            val viewModel: MissingLinkViewModel = viewModel(factory = container.missingLinkViewModelFactory(hadithId))
            MissingLinkRoute(viewModel, onClose = { navController.popBackStack() })
        }

        composable(Routes.NARRATOR_GRAPH) {
            val viewModel: NarratorGraphViewModel = viewModel(factory = container.narratorGraphViewModelFactory())
            NarratorGraphRoute(
                viewModel = viewModel,
                onClose = { navController.popBackStack() },
                onReviewNarrator = { navController.navigate(Routes.review(ReviewMode.NARRATOR, it)) },
                onOpenHadith = { navController.navigate(Routes.hadithDetails(it)) },
            )
        }

        composable(Routes.ANALYTICS) {
            val viewModel: AnalyticsViewModel = viewModel(factory = container.analyticsViewModelFactory())
            AnalyticsRoute(
                viewModel = viewModel,
                onClose = { navController.popBackStack() },
                onDrillNarrator = { navController.navigate(Routes.review(ReviewMode.NARRATOR, it)) },
            )
        }

        composable(Routes.LIBRARY) {
            val viewModel: LibraryViewModel = viewModel(factory = container.libraryViewModelFactory())
            LibraryRoute(
                viewModel = viewModel,
                // A tab, not a page above another: there is nowhere to go back to.
                onClose = null,
                onOpenHadith = { navController.navigate(Routes.hadithStudy(it)) },
            )
        }

        composable(
            route = Routes.HADITH_STUDY,
            arguments = listOf(navArgument(Routes.ARG_HADITH_ID) { type = NavType.LongType }),
        ) { entry ->
            val hadithId = entry.arguments?.getLong(Routes.ARG_HADITH_ID) ?: return@composable
            val viewModel: HadithStudyViewModel = viewModel(factory = container.hadithStudyViewModelFactory(hadithId))
            HadithStudyRoute(
                viewModel = viewModel,
                onClose = { navController.popBackStack() },
                // Back from the learning flow returns to the study page, which by then shows the hadith as in study.
                onStartLearning = { navController.navigate(Routes.review(ReviewMode.NEW, it)) },
                onReview = { navController.navigate(Routes.review(ReviewMode.SINGLE, it)) },
            )
        }

        composable(Routes.SETTINGS) {
            val viewModel: SettingsViewModel = viewModel(factory = container.settingsViewModelFactory())
            SettingsRoute(viewModel, onClose = { navController.popBackStack() })
        }

        dialog(
            route = Routes.HADITH_DETAILS,
            arguments = listOf(navArgument(Routes.ARG_HADITH_ID) { type = NavType.LongType }),
            dialogProperties = DialogProperties(usePlatformDefaultWidth = false),
        ) { entry ->
            val hadithId = entry.arguments?.getLong(Routes.ARG_HADITH_ID) ?: return@dialog
            val viewModel: HadithDetailsViewModel = viewModel(factory = container.hadithDetailsViewModelFactory(hadithId))
            HadithDetailsRoute(
                viewModel = viewModel,
                onClose = { navController.popBackStack() },
                // Leave the dialog behind so Back from the review returns to where the user came from.
                onReview = { id ->
                    navController.navigate(Routes.review(ReviewMode.SINGLE, id)) {
                        popUpTo(Routes.HADITH_DETAILS) { inclusive = true }
                    }
                },
            )
        }
    }
}
