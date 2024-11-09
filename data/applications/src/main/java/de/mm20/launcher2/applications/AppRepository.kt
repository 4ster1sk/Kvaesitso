package de.mm20.launcher2.applications

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import de.mm20.launcher2.ktx.normalize
import de.mm20.launcher2.profiles.Profile
import de.mm20.launcher2.profiles.ProfileManager
import de.mm20.launcher2.search.Application
import de.mm20.launcher2.search.ResultScore
import de.mm20.launcher2.search.SearchableRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.text.similarity.FuzzyScore
import java.util.Locale
import dev.esnault.wanakana.core.Wanakana

interface AppRepository : SearchableRepository<Application> {
    fun findOne(
        packageName: String,
        user: UserHandle,
    ): Flow<Application?>

    fun findMany(): Flow<ImmutableList<Application>>
}

internal class AppRepositoryImpl(
    private val context: Context,
    private val profileManager: ProfileManager,
) : AppRepository {
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    private val launcherApps =
        context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps

    private val installedApps = MutableStateFlow<List<LauncherApp>>(emptyList())

    private val profiles = profileManager.activeProfiles

    private val mutex = Mutex()

    init {
        launcherApps.registerCallback(object : LauncherApps.Callback() {
            override fun onPackagesUnavailable(
                packageNames: Array<out String>,
                user: UserHandle,
                replacing: Boolean
            ) {
                scope.launch {
                    mutex.withLock {
                        val apps = installedApps.value.toMutableList()
                        apps.removeAll { packageNames.contains(it.componentName.packageName) && it.user == user }
                        installedApps.value = apps
                    }
                }
            }

            override fun onPackageChanged(packageName: String, user: UserHandle) {
                scope.launch {
                    mutex.withLock {
                        val apps = installedApps.value.toMutableList()
                        apps.removeAll { packageName == it.componentName.packageName && it.user == user }
                        apps.addAll(getApplications(packageName, user))
                        installedApps.value = apps
                    }
                }
            }

            override fun onPackagesAvailable(
                packageNames: Array<out String>,
                user: UserHandle,
                replacing: Boolean
            ) {
                scope.launch {
                    mutex.withLock {
                        val apps = installedApps.value.toMutableList()
                        for (packageName in packageNames) {
                            apps.addAll(getApplications(packageName, user))
                        }
                        installedApps.value = apps
                    }
                }
            }

            override fun onPackageAdded(packageName: String, user: UserHandle) {
                scope.launch {
                    mutex.withLock {
                        val apps = installedApps.value.toMutableList()
                        apps.addAll(getApplications(packageName, user))
                        installedApps.value = apps
                    }
                }
            }

            override fun onPackageRemoved(packageName: String, user: UserHandle) {
                scope.launch {
                    mutex.withLock {
                        val apps = installedApps.value.toMutableList()
                        apps.removeAll { packageName == it.componentName.packageName && it.user == user }
                        installedApps.value = apps

                    }
                }
            }

            override fun onShortcutsChanged(
                packageName: String,
                shortcuts: MutableList<ShortcutInfo>,
                user: UserHandle
            ) {
                onPackageChanged(packageName, user)
            }

            override fun onPackagesSuspended(packageNames: Array<out String>?, user: UserHandle?) {
                packageNames ?: return
                scope.launch {
                    mutex.withLock {
                        val apps = installedApps.value.toMutableList()
                        apps.replaceAll {
                            if (packageNames.contains(it.componentName.packageName) && it.user == user) {
                                it.copy(isSuspended = true)
                            } else {
                                it
                            }
                        }
                        installedApps.value = apps
                    }
                }
            }

            override fun onPackagesUnsuspended(
                packageNames: Array<out String>?,
                user: UserHandle?
            ) {
                packageNames ?: return
                scope.launch {
                    mutex.withLock {
                        val apps = installedApps.value.toMutableList()
                        apps.replaceAll {
                            if (packageNames.contains(it.componentName.packageName) && it.user == user) {
                                it.copy(isSuspended = false)
                            } else {
                                it
                            }
                        }
                        installedApps.value = apps
                    }
                }
            }

        }, Handler(Looper.getMainLooper()))
        scope.launch {
            profiles.runningFold<List<Profile>, Pair<List<Profile>?, List<Profile>?>>(null to null) { acc, value ->
                acc.second to value
            }.collectLatest { (prev, curr) ->
                if (curr == null) return@collectLatest
                if (prev == null) {
                    curr.forEach { addProfile(it) }
                } else {
                    val added = curr - prev
                    val removed = prev - curr
                    added.forEach { addProfile(it) }
                    removed.forEach { removeProfile(it) }
                }
            }
        }
    }

    private suspend fun addProfile(profile: Profile) {
        mutex.withLock {
            val apps = installedApps.value.toMutableList()
            apps.addAll(getApplications(null, profile.userHandle))
            installedApps.value = apps
        }
    }

    private fun removeProfile(profile: Profile) {
        scope.launch {
            mutex.withLock {
                val apps = installedApps.value.toMutableList()
                apps.removeAll { it.user == profile.userHandle }
                installedApps.value = apps
            }
        }
    }

    private fun getApplications(packageName: String?, userHandle: UserHandle): List<LauncherApp> {
        if (packageName == context.packageName) return emptyList()

        return try {
            launcherApps.getActivityList(packageName, userHandle)
                .mapNotNull { getApplication(it) }
        } catch (e: SecurityException) {
            emptyList()
        }
    }


    private fun getApplication(
        launcherActivityInfo: LauncherActivityInfo
    ): LauncherApp? {
        if (launcherActivityInfo.applicationInfo.packageName == context.packageName && !context.packageName.endsWith(
                ".debug"
            )
        ) return null
        return LauncherApp(context, launcherActivityInfo)
    }

    override fun findOne(
        packageName: String,
        user: UserHandle,
    ): Flow<Application?> {
        return installedApps.map {
            it.firstOrNull {
                it.componentName.packageName == packageName && it.user == user
            }
        }
    }

    override fun findMany(): Flow<ImmutableList<Application>> {
        return installedApps.map { it.toImmutableList() }
    }

    override fun search(query: String, allowNetwork: Boolean): Flow<ImmutableList<LauncherApp>> {
        val queryRomaji = toRomaji(query);
        return installedApps.map { apps ->
            withContext(Dispatchers.Default) {
                val appResults = mutableListOf<LauncherApp>()
                if (query.isEmpty()) {
                    appResults.addAll(apps)
                } else {
                    appResults.addAll(apps.mapNotNull {
                        val score = ResultScore(
                            query = query,
                            primaryFields = listOf(it.label),
                        )
                        if (score.score < 0.8f) return@mapNotNull null
                        it.copy(
                            score = score
                        )
                    })
                    appResults.addAll(apps.filter { matches(toRomaji(it.label), queryRomaji) })

                    val componentName = ComponentName.unflattenFromString(query)
                    getActivityByComponentName(componentName)?.let { appResults.add(it) }
                }
                appResults.sort()
                appResults.distinctBy { it.label }.toImmutableList()
            }
        }
    }

    private fun toRomaji(label: String): String {
        val halfWidthToFullWidthMap = mapOf(
            'ｱ' to 'ア', 'ｲ' to 'イ', 'ｳ' to 'ウ', 'ｴ' to 'エ', 'ｵ' to 'オ',
            'ｶ' to 'カ', 'ｷ' to 'キ', 'ｸ' to 'ク', 'ｹ' to 'ケ', 'ｺ' to 'コ',
            'ｻ' to 'サ', 'ｼ' to 'シ', 'ｽ' to 'ス', 'ｾ' to 'セ', 'ｿ' to 'ソ',
            'ﾀ' to 'タ', 'ﾁ' to 'チ', 'ﾂ' to 'ツ', 'ﾃ' to 'テ', 'ﾄ' to 'ト',
            'ﾅ' to 'ナ', 'ﾆ' to 'ニ', 'ﾇ' to 'ヌ', 'ﾈ' to 'ネ', 'ﾉ' to 'ノ',
            'ﾊ' to 'ハ', 'ﾋ' to 'ヒ', 'ﾌ' to 'フ', 'ﾍ' to 'ヘ', 'ﾎ' to 'ホ',
            'ﾏ' to 'マ', 'ﾐ' to 'ミ', 'ﾑ' to 'ム', 'ﾒ' to 'メ', 'ﾓ' to 'モ',
            'ﾔ' to 'ヤ', 'ﾕ' to 'ユ', 'ﾖ' to 'ヨ',
            'ﾗ' to 'ラ', 'ﾘ' to 'リ', 'ﾙ' to 'ル', 'ﾚ' to 'レ', 'ﾛ' to 'ロ',
            'ﾜ' to 'ワ', 'ｦ' to 'ヲ', 'ﾝ' to 'ン',
            'ｧ' to 'ァ', 'ｨ' to 'ィ', 'ｩ' to 'ゥ', 'ｪ' to 'ェ', 'ｫ' to 'ォ',
            'ｬ' to 'ャ', 'ｭ' to 'ュ', 'ｮ' to 'ョ', 'ｯ' to 'ッ',
            'ﾞ' to '゛', 'ﾟ' to '゜'
        )

        val builder = StringBuilder()
        for (char in label) {
            builder.append(halfWidthToFullWidthMap[char] ?: char)
        }

        return Wanakana.toRomaji(builder.toString())
    }

    private fun matches(label: String, query: String): Boolean {
        val normalizedLabel = label.normalize()
        val normalizedQuery = query.normalize()
        if (normalizedLabel.contains(normalizedQuery)) return true
        val fuzzyScore = FuzzyScore(Locale.getDefault())
        return fuzzyScore.fuzzyScore(normalizedLabel, normalizedQuery) >= query.length * 1.5
    }

    private fun getActivityByComponentName(componentName: ComponentName?): LauncherApp? {
        componentName ?: return null
        val intent = Intent().setComponent(componentName)
        val lai = launcherApps.resolveActivity(intent, Process.myUserHandle())
        return lai?.let {
            LauncherApp(context, lai)
        }
    }
}