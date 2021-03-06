/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.coreedge.raft.replication.token;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import org.neo4j.coreedge.server.core.RecoverTransactionLogState;
import org.neo4j.graphdb.TransactionFailureException;
import org.neo4j.kernel.impl.api.TransactionRepresentationCommitProcess;
import org.neo4j.kernel.impl.api.TransactionToApply;
import org.neo4j.kernel.impl.store.record.LabelTokenRecord;
import org.neo4j.kernel.impl.transaction.TransactionRepresentation;
import org.neo4j.kernel.impl.transaction.command.Command;
import org.neo4j.kernel.impl.transaction.log.TransactionAppender;
import org.neo4j.kernel.impl.transaction.tracing.CommitEvent;
import org.neo4j.kernel.impl.util.Dependencies;
import org.neo4j.logging.NullLogProvider;
import org.neo4j.storageengine.api.StorageCommand;
import org.neo4j.storageengine.api.StorageEngine;
import org.neo4j.storageengine.api.Token;
import org.neo4j.storageengine.api.TransactionApplicationMode;

import static java.util.Collections.singletonList;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.neo4j.coreedge.raft.replication.token.ReplicatedTokenRequestSerializer.commandBytes;
import static org.neo4j.coreedge.raft.replication.token.TokenType.LABEL;
import static org.neo4j.coreedge.raft.replication.tx.LogIndexTxHeaderEncoding.decodeLogIndexFromTxHeader;

public class ReplicatedTokenStateMachineTest
{
    final int EXPECTED_TOKEN_ID = 1;
    final int UNEXPECTED_TOKEN_ID = 1024;

    private Dependencies dependencies = mock( Dependencies.class );

    @Test
    public void shouldCreateTokenId() throws Exception
    {
        // given
        when( dependencies.resolveDependency( TransactionRepresentationCommitProcess.class ) )
                .thenReturn( mock( TransactionRepresentationCommitProcess.class ) );

        TokenRegistry<Token> registry = new TokenRegistry<>( "Label" );
        ReplicatedTokenStateMachine<Token> stateMachine = new ReplicatedTokenStateMachine<>( registry, dependencies,
                new Token.Factory(), LABEL, NullLogProvider.getInstance(), mock( RecoverTransactionLogState.class ) );

        // when
        stateMachine.applyCommand( new ReplicatedTokenRequest( LABEL, "Person",
                commandBytes( tokenCommands( EXPECTED_TOKEN_ID ) ) ), 1 );

        // then
        assertEquals( EXPECTED_TOKEN_ID, (int) registry.getId( "Person" ) );
    }

    @Test
    public void shouldAllocateTokenIdToFirstReplicateRequest() throws Exception
    {
        // given
        when( dependencies.resolveDependency( TransactionRepresentationCommitProcess.class ) )
                .thenReturn( mock( TransactionRepresentationCommitProcess.class ) );

        TokenRegistry<Token> registry = new TokenRegistry<>( "Label" );
        ReplicatedTokenStateMachine<Token> stateMachine = new ReplicatedTokenStateMachine<>( registry, dependencies,
                new Token.Factory(), LABEL, NullLogProvider.getInstance(), mock( RecoverTransactionLogState.class ) );

        ReplicatedTokenRequest winningRequest =
                new ReplicatedTokenRequest( LABEL, "Person", commandBytes( tokenCommands( EXPECTED_TOKEN_ID ) ) );
        ReplicatedTokenRequest losingRequest =
                new ReplicatedTokenRequest( LABEL, "Person", commandBytes( tokenCommands( UNEXPECTED_TOKEN_ID ) ) );

        // when
        stateMachine.applyCommand( winningRequest, 1 );
        stateMachine.applyCommand( losingRequest, 2 );

        // then
        assertEquals( EXPECTED_TOKEN_ID, (int) registry.getId( "Person" ) );
    }

    @Test
    public void shouldStoreRaftLogIndexInTransactionHeader() throws Exception
    {
        // given
        int logIndex = 1;

        StubTransactionCommitProcess commitProcess = new StubTransactionCommitProcess( null, null );
        when( dependencies.resolveDependency( TransactionRepresentationCommitProcess.class ) ).thenReturn(
                commitProcess );

        ReplicatedTokenStateMachine<Token> stateMachine = new ReplicatedTokenStateMachine<>(
                new TokenRegistry<>( "Token" ), dependencies, new Token.Factory(), LABEL,
                NullLogProvider.getInstance(), mock( RecoverTransactionLogState.class ) );

        // when
        ReplicatedTokenRequest tokenRequest = new ReplicatedTokenRequest( LABEL, "Person",
                commandBytes( tokenCommands( EXPECTED_TOKEN_ID ) ) );
        stateMachine.applyCommand( tokenRequest, logIndex );

        // then
        List<TransactionRepresentation> transactions = commitProcess.transactionsToApply;
        assertEquals( 1, transactions.size() );
        assertEquals( logIndex, decodeLogIndexFromTxHeader( transactions.get( 0 ).additionalHeader() ) );
    }

    private static List<StorageCommand> tokenCommands( int expectedTokenId )
    {
        return singletonList( new Command.LabelTokenCommand(
                new LabelTokenRecord( expectedTokenId ),
                new LabelTokenRecord( expectedTokenId )
        ) );
    }

    private static class StubTransactionCommitProcess extends TransactionRepresentationCommitProcess
    {
        private final List<TransactionRepresentation> transactionsToApply = new ArrayList<>();

        public StubTransactionCommitProcess( TransactionAppender appender, StorageEngine storageEngine )
        {
            super( appender, storageEngine );
        }

        @Override
        public long commit( TransactionToApply batch, CommitEvent commitEvent, TransactionApplicationMode mode )
                throws TransactionFailureException
        {
            transactionsToApply.add( batch.transactionRepresentation() );
            return -1;
        }
    }
}
