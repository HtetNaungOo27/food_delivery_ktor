package com.codewithfk.services

import com.codewithfk.database.RestaurantsTable
import com.codewithfk.database.ReviewsTable
import com.codewithfk.database.UsersTable
import com.codewithfk.model.RestaurantReview
import com.codewithfk.model.ReviewSummary
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.javatime.CurrentDateTime
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

object ReviewService {
    fun getReviews(restaurantId: UUID): ReviewSummary = transaction {
        val reviews = (ReviewsTable innerJoin UsersTable)
            .select { ReviewsTable.restaurantId eq restaurantId }
            .orderBy(ReviewsTable.updatedAt, SortOrder.DESC)
            .map {
                RestaurantReview(
                    id = it[ReviewsTable.id].toString(),
                    userId = it[ReviewsTable.userId].toString(),
                    userName = it[UsersTable.name],
                    restaurantId = it[ReviewsTable.restaurantId].toString(),
                    rating = it[ReviewsTable.rating],
                    comment = it[ReviewsTable.comment],
                    createdAt = it[ReviewsTable.createdAt].toString()
                )
            }
        ReviewSummary(
            averageRating = if (reviews.isEmpty()) 0.0 else reviews.map { it.rating }.average(),
            reviewCount = reviews.size,
            reviews = reviews
        )
    }

    fun saveReview(userId: UUID, restaurantId: UUID, rating: Int, comment: String): ReviewSummary = transaction {
        require(rating in 1..5) { "Rating must be between 1 and 5" }
        require(comment.isNotBlank()) { "Review comment is required" }
        check(RestaurantsTable.select { RestaurantsTable.id eq restaurantId }.any()) { "Restaurant not found" }

        val existing = ReviewsTable.select {
            (ReviewsTable.userId eq userId) and (ReviewsTable.restaurantId eq restaurantId)
        }.singleOrNull()
        if (existing == null) {
            ReviewsTable.insert {
                it[this.userId] = userId
                it[this.restaurantId] = restaurantId
                it[this.rating] = rating
                it[this.comment] = comment.trim()
            }
        } else {
            ReviewsTable.update({ ReviewsTable.id eq existing[ReviewsTable.id] }) {
                it[this.rating] = rating
                it[this.comment] = comment.trim()
                it[updatedAt] = CurrentDateTime()
            }
        }
        getReviews(restaurantId)
    }
}
