package com.zhhz.spider.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface RuleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveRule(rule: RuleEntity)

    @Query("SELECT * FROM rules ORDER BY updateTime DESC")
    fun getAllRulesFlow(): Flow<List<RuleEntity>>

    @Query("SELECT * FROM rules WHERE id = :id")
    suspend fun getRuleById(id: String): RuleEntity?

    @Delete
    suspend fun deleteRule(rule: RuleEntity)

    @Query("SELECT * FROM rule_sessions WHERE ruleId = :ruleId")
    suspend fun getSession(ruleId: String): SessionEntity?

    /**
     * 观察特定规则的会话状态
     * 当该规则的 Session 被保存、更新或删除时，Flow 会立即发出新信号
     */
    @Query("SELECT * FROM rule_sessions WHERE ruleId = :ruleId")
    fun getSessionFlow(ruleId: String): Flow<SessionEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSession(session: SessionEntity)

    @Query("DELETE FROM rule_sessions WHERE ruleId = :ruleId")
    suspend fun deleteSession(ruleId: String)

}