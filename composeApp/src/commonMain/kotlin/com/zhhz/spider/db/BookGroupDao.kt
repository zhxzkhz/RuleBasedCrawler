package com.zhhz.spider.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookGroupDao {

    @Query("SELECT * FROM book_groups ORDER BY mask ASC")
    fun observeAll(): Flow<List<BookGroupEntity>>

    @Query("SELECT * FROM book_groups ORDER BY mask ASC")
    suspend fun getAll(): List<BookGroupEntity>

    @Query("SELECT * FROM book_groups WHERE id = :id")
    suspend fun getById(id: Long): BookGroupEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(group: BookGroupEntity): Long

    @Update
    suspend fun update(group: BookGroupEntity)

    @Delete
    suspend fun delete(group: BookGroupEntity)
}
