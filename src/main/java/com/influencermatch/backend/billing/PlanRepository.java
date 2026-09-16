package com.influencermatch.backend.billing; import org.springframework.data.jpa.repository.JpaRepository; import java.util.UUID; public interface PlanRepository extends JpaRepository<Plan,UUID>{}
