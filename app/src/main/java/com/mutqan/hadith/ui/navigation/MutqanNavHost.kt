package com.mutqan.hadith.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mutqan.hadith.di.AppContainer
import com.mutqan.hadith.ui.home.HomeActions
import com.mutqan.hadith.ui.home.HomeRoute
import com.mutqan.hadith.ui.home.HomeViewModel
import com.mutqan.hadith.ui.homeViewModelFactory
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

object Routes {
    const val HOME = "home"

    const val ARG_MODE = "mode"
    const val ARG_HADITH_ID = "hadithId"
    const val NO_HADITH = -1L

    const val REVIEW = "review/{$ARG_MODE}?$ARG_HADITH_ID={$ARG_HADITH_ID}"
    const val SANAD_BUILDER = "sanad_builder/{$ARG_HADITH_ID}"
    const val MISSING_LINK = "missing_link/{$ARG_HADITH_ID}"

    fun review(mode: ReviewMode, hadithId: Long? = null): String =
        "review/${mode.route}" + if (hadithId != null) "?$ARG_HADITH_ID=$hadithId" else ""

    fun sanadBuilder(hadithId: Long): String = "sanad_builder/$hadithId"

    fun missingLink(hadithId: Long): String = "missing_link/$hadithId"
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

@Composable
fun MutqanNavHost(
    container: AppContainer,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier,
        enterTransition = forwardIn,
        exitTransition = forwardOut,
        popEnterTransition = backIn,
        popExitTransition = backOut,
    ) {
        composable(Routes.HOME) {
            val viewModel: HomeViewModel = viewModel(factory = container.homeViewModelFactory())
            HomeRoute(
                viewModel = viewModel,
                actions = HomeActions(
                    onStartDueReview = { navController.navigate(Routes.review(ReviewMode.DUE)) },
                    onLearnNew = { navController.navigate(Routes.review(ReviewMode.NEW)) },
                    onReviewHadith = { navController.navigate(Routes.review(ReviewMode.SINGLE, it)) },
                    onSanadBuilder = { navController.navigate(Routes.sanadBuilder(it)) },
                    onMissingLink = { navController.navigate(Routes.missingLink(it)) },
                ),
            )
        }

        composable(
            route = Routes.REVIEW,
            arguments = listOf(
                navArgument(Routes.ARG_MODE) { type = NavType.StringType },
                navArgument(Routes.ARG_HADITH_ID) { type = NavType.LongType; defaultValue = Routes.NO_HADITH },
            ),
        ) { entry ->
            val mode = ReviewMode.fromRoute(entry.arguments?.getString(Routes.ARG_MODE))
            val hadithId = entry.arguments?.getLong(Routes.ARG_HADITH_ID)?.takeIf { it != Routes.NO_HADITH }
            val viewModel: ReviewViewModel = viewModel(factory = container.reviewViewModelFactory(mode, hadithId))
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
    }
}
