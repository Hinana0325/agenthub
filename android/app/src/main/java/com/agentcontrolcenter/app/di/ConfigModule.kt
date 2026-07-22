package com.agentcontrolcenter.app.di

import android.content.Context
import com.agentcontrolcenter.app.core.config.ConfigRepository
import com.agentcontrolcenter.app.core.config.ConfigRepositoryImpl
import com.agentcontrolcenter.app.core.datastore.SettingsDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ConfigModule {

    /**
     * 绑定 [ConfigRepository] 接口到 [ConfigRepositoryImpl]。
     *
     * 上层通过 Hilt 注入 [ConfigRepository] 接口（而非具体实现），便于单元测试
     * 替换为内存实现。与 iOS 端通过协议注入 `AppPreferences` 的模式对齐。
     */
    @Provides @Singleton
    fun provideConfigRepository(
        @ApplicationContext context: Context,
        settingsDataStore: SettingsDataStore
    ): ConfigRepository = ConfigRepositoryImpl(context, settingsDataStore)
}
