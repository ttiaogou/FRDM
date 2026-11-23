package com.freedom.features.aireply.policy

import com.freedom.features.aireply.PrivateMessagesTaskController
import com.freedom.features.aireply.ReceivedGreetingsTaskController
import com.freedom.features.aireply.ai.AiReplyModelContracts
import org.junit.Assert.*
import org.junit.Test

class AiReplyPolicyRegistryTest {
    private val registry: PolicyRegistry = AiReplyPolicyRegistry

    @Test fun resolvesOnlyRegisteredTasksByTheirExactExistingOwnerIds() {
        assertSame(ReceivedGreetingsPolicy, registry.resolve(ReceivedGreetingsTaskController.TASK_ID))
        assertSame(PrivateMessagesPolicy, registry.resolve(PrivateMessagesTaskController.TASK_ID))
        assertNotSame(registry.resolve(ReceivedGreetingsTaskController.TASK_ID),
            registry.resolve(PrivateMessagesTaskController.TASK_ID))
        for (unknown in listOf("", "UNKNOWN", "AI_REPLY", "AI_REPLY_PRIVATE_MESSAGES ",
                "AI_REPLY_RECEIVED_GREETINGS ", "AI_REPLY_ACCOUNT_BROWSE")) {
            assertNull(unknown, registry.resolve(unknown))
        }
    }

    @Test fun registryPreservesExistingModelContractAndHistoryBoundaries() {
        val greeting = registry.resolve(ReceivedGreetingsTaskController.TASK_ID)!!
        val privateMessage = registry.resolve(PrivateMessagesTaskController.TASK_ID)!!
        assertEquals(AiReplyModelContracts.currentOutput(), greeting.modelContract())
        assertEquals(AiReplyModelContracts.currentOutput(), privateMessage.modelContract())
        assertNull(greeting.contextPolicy().verifiedHistoryIdentity(null))
        assertNull(greeting.contextPolicy().selectHistory(null))
    }
}
