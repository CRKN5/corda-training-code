package com.template.proposal.service;

import com.r3.corda.lib.accounts.contracts.states.AccountInfo;
import com.r3.corda.lib.accounts.workflows.flows.CreateAccount;
import com.r3.corda.lib.accounts.workflows.flows.RequestKeyForAccount;
import com.r3.corda.lib.ci.workflows.SyncKeyMappingInitiator;
import com.r3.corda.lib.tokens.contracts.states.FungibleToken;
import com.r3.corda.lib.tokens.contracts.states.NonFungibleToken;
import com.r3.corda.lib.tokens.contracts.types.IssuedTokenType;
import com.r3.corda.lib.tokens.contracts.types.TokenPointer;
import com.r3.corda.lib.tokens.contracts.types.TokenType;
import com.r3.corda.lib.tokens.contracts.utilities.AmountUtilitiesKt;
import com.r3.corda.lib.tokens.money.FiatCurrency;
import com.r3.corda.lib.tokens.workflows.flows.rpc.IssueTokens;
import com.template.car.flow.*;
import com.template.car.state.CarTokenType;
import com.template.proposal.flow.SalesProposalAcceptFlows.AcceptSimpleFlow;
import com.template.proposal.flow.SalesProposalOfferFlows.OfferSimpleFlow;
import com.template.proposal.flow.SalesProposalRejectFlows.RejectSimpleFlow;
import com.template.proposal.state.SalesProposal;
import net.corda.core.concurrent.CordaFuture;
import net.corda.core.contracts.Amount;
import net.corda.core.contracts.StateAndRef;
import net.corda.core.identity.AbstractParty;
import net.corda.core.identity.AnonymousParty;
import net.corda.core.identity.CordaX500Name;
import net.corda.core.identity.Party;
import net.corda.core.node.services.vault.QueryCriteria;
import net.corda.core.transactions.SignedTransaction;
import net.corda.testing.node.MockNetwork;
import net.corda.testing.node.MockNodeParameters;
import net.corda.testing.node.StartedMockNode;
import net.corda.testing.node.TestClock;
import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.security.PublicKey;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class SalesProposalServiceTests {
    private final MockNetwork network;
    private final StartedMockNode notary;
    private final StartedMockNode usMint;
    private final StartedMockNode dmv;
    private final StartedMockNode bmwDealer;
    private final StartedMockNode alice;
    private final StartedMockNode bob;
    private final IssuedTokenType usMintUsd;

    public SalesProposalServiceTests() {
        network = new MockNetwork(CarTokenCourseHelpers.prepareMockNetworkParameters());
        notary = network.getDefaultNotaryNode();
        usMint = network.createNode(new MockNodeParameters()
                .withLegalName(UsdTokenConstants.US_MINT));
        dmv = network.createNode(new MockNodeParameters()
                .withLegalName(CarTokenTypeConstants.DMV));
        bmwDealer = network.createNode(new MockNodeParameters()
                .withLegalName(CarTokenTypeConstants.BMW_DEALER));
        alice = network.createNode(new MockNodeParameters()
                .withLegalName(CordaX500Name.parse("O=Alice, L=Istanbul, C=TR")));
        bob = network.createNode(new MockNodeParameters()
                .withLegalName(CordaX500Name.parse("O=Bob, L=Paris, C=FR")));
        final TokenType usdTokenType = FiatCurrency.Companion.getInstance("USD");
        usMintUsd = new IssuedTokenType(usMint.getInfo().getLegalIdentities().get(0), usdTokenType);
    }

    @Before
    public void setup() {
        network.runNetwork();
    }

    @After
    public void tearDown() {
        network.stopNodes();
    }

    @NotNull
    private StateAndRef<AccountInfo> createAccount(
            @NotNull final StartedMockNode host,
            @NotNull final String name) throws Exception {
        final CordaFuture<StateAndRef<? extends AccountInfo>> future = host.startFlow(
                new CreateAccount(name));
        network.runNetwork();
        //noinspection unchecked
        return (StateAndRef<AccountInfo>) future.get();
    }

    @NotNull
    private AnonymousParty requestNewKey(
            @NotNull final StartedMockNode host,
            @NotNull final AccountInfo forWhom) throws Exception {
        final CordaFuture<AnonymousParty> future = host.startFlow(new RequestKeyForAccount(forWhom));
        network.runNetwork();
        return future.get();
    }

    private void informKeys(
            @NotNull final StartedMockNode host,
            @NotNull final List<PublicKey> who,
            @NotNull final List<StartedMockNode> others) throws Exception {
        for (StartedMockNode other : others) {
            final CordaFuture<?> future = host.startFlow(new SyncKeyMappingInitiator(
                    other.getInfo().getLegalIdentities().get(0),
                    who.stream()
                            .distinct()
                            .map(AnonymousParty::new)
                            .collect(Collectors.toList())));
            network.runNetwork();
            future.get();
        }
    }

    @NotNull
    private SignedTransaction createNewBmw(
            @SuppressWarnings("SameParameterValue") @NotNull final String vin,
            @SuppressWarnings("SameParameterValue") @NotNull final String make,
            @NotNull final List<Party> observers) throws Exception {
        final IssueCarTokenTypeFlows.IssueCarTokenTypeFlow flow = new IssueCarTokenTypeFlows.IssueCarTokenTypeFlow(notary.getInfo().getLegalIdentities().get(0),
                vin, make, observers);
        final CordaFuture<SignedTransaction> future = dmv.startFlow(flow);
        network.runNetwork();
        return future.get();
    }

    @NotNull
    private SignedTransaction issueCarTo(
            @NotNull final TokenPointer<CarTokenType> car,
            @NotNull final AbstractParty holder) throws Exception {
        final IssueCarToHolderFlows.IssueCarToHolderFlow flow = new IssueCarToHolderFlows.IssueCarToHolderFlow(
                car, bmwDealer.getInfo().getLegalIdentities().get(0), holder);
        final CordaFuture<SignedTransaction> future = bmwDealer.startFlow(flow);
        network.runNetwork();
        return future.get();
    }

    @SuppressWarnings({"SameParameterValue", "UnusedReturnValue"})
    @NotNull
    private SignedTransaction updateMileageOn(
            @NotNull final StateAndRef<CarTokenType> carRef,
            @SuppressWarnings("SameParameterValue") final long mileage,
            final long price,
            @NotNull final List<Party> observers) throws Exception {
        final UpdateCarTokenTypeFlows.UpdateCarTokenTypeFlow flow = new UpdateCarTokenTypeFlows.UpdateCarTokenTypeFlow(carRef, mileage, price, observers);
        final CordaFuture<SignedTransaction> future = dmv.startFlow(flow);
        network.runNetwork();
        return future.get();
    }

    @Test
    public void carCreatedDoesNotAddToTracker() throws Exception {
        // Seller is on alice.
        final StateAndRef<AccountInfo> seller = createAccount(alice, "carly");
        final AnonymousParty sellerParty = requestNewKey(alice, seller.getState().getData());
        informKeys(alice, Collections.singletonList(sellerParty.getOwningKey()), Collections.singletonList(bmwDealer));
        // Buyer is on bob.
        final StateAndRef<AccountInfo> buyer = createAccount(bob, "dan");
        final AnonymousParty buyerParty = requestNewKey(bob, buyer.getState().getData());
        informKeys(bob, Collections.singletonList(buyerParty.getOwningKey()), Collections.singletonList(alice));
        // The car.
        final StateAndRef<CarTokenType> bmwType = createNewBmw("abc124", "BMW",
                Arrays.asList(
                        bmwDealer.getInfo().getLegalIdentities().get(0),
                        alice.getInfo().getLegalIdentities().get(0),
                        bob.getInfo().getLegalIdentities().get(0)))
                .getCoreTransaction().outRefsOfType(CarTokenType.class).get(0);
        issueCarTo(bmwType.getState().getData().toPointer(CarTokenType.class), sellerParty);

        // No one is tracking.
        Arrays.asList(dmv, bmwDealer, alice, bob).forEach(node -> {
            final SalesProposalService proposalService = node.getServices().cordaService(SalesProposalService.class);
            assertEquals(0, proposalService.getCarTypeCount());
        });
    }

    @Test
    public void proposalCreatedAddsToTrackerOnSellerOnly() throws Exception {
        // Seller is on alice.
        final StateAndRef<AccountInfo> seller = createAccount(alice, "carly");
        final AnonymousParty sellerParty = requestNewKey(alice, seller.getState().getData());
        informKeys(alice, Collections.singletonList(sellerParty.getOwningKey()), Collections.singletonList(bmwDealer));
        // Buyer is on bob.
        final StateAndRef<AccountInfo> buyer = createAccount(bob, "dan");
        final AnonymousParty buyerParty = requestNewKey(bob, buyer.getState().getData());
        informKeys(bob, Collections.singletonList(buyerParty.getOwningKey()), Collections.singletonList(alice));
        // The car.
        final StateAndRef<CarTokenType> bmwType = createNewBmw("abc124", "BMW",
                Arrays.asList(
                        bmwDealer.getInfo().getLegalIdentities().get(0),
                        alice.getInfo().getLegalIdentities().get(0),
                        bob.getInfo().getLegalIdentities().get(0)))
                .getCoreTransaction().outRefsOfType(CarTokenType.class).get(0);
        final StateAndRef<NonFungibleToken> bmw1 = issueCarTo(
                bmwType.getState().getData().toPointer(CarTokenType.class),
                sellerParty)
                .getCoreTransaction().outRefsOfType(NonFungibleToken.class).get(0);

        // Seller makes an offer.
        final OfferSimpleFlow offerFlow = new OfferSimpleFlow(
                bmw1.getState().getData().getLinearId(), buyerParty, 11_000L, "USD",
                usMint.getInfo().getLegalIdentities().get(0), 3600);
        final CordaFuture<SignedTransaction> offerFuture = alice.startFlow(offerFlow);
        network.runNetwork();
        offerFuture.get();

        // Only alice tracks the car.
        Arrays.asList(dmv, bmwDealer, bob).forEach(node -> {
            final SalesProposalService proposalService = node.getServices().cordaService(SalesProposalService.class);
            assertEquals(0, proposalService.getCarTypeCount());
        });
        final SalesProposalService aliceService = alice.getServices().cordaService(SalesProposalService.class);
        assertEquals(1, aliceService.getCarTypeCount());
        final List<AbstractParty> buyers = aliceService.getBuyersOf(bmwType);
        assertNotNull(buyers);
        assertEquals(1, buyers.size());
        assertEquals(buyerParty, buyers.get(0));
    }

    @Test
    public void whenCarUpdatedItIsSentToBuyer() throws Exception {
        // Seller is on alice.
        final StateAndRef<AccountInfo> seller = createAccount(alice, "carly");
        final AnonymousParty sellerParty = requestNewKey(alice, seller.getState().getData());
        informKeys(alice, Collections.singletonList(sellerParty.getOwningKey()), Collections.singletonList(bmwDealer));
        // Buyer is on bob.
        final StateAndRef<AccountInfo> buyer = createAccount(bob, "dan");
        final AnonymousParty buyerParty = requestNewKey(bob, buyer.getState().getData());
        informKeys(bob, Collections.singletonList(buyerParty.getOwningKey()), Collections.singletonList(alice));
        // The car.
        final StateAndRef<CarTokenType> bmwType = createNewBmw("abc124", "BMW",
                Arrays.asList(
                        bmwDealer.getInfo().getLegalIdentities().get(0),
                        alice.getInfo().getLegalIdentities().get(0),
                        bob.getInfo().getLegalIdentities().get(0)))
                .getCoreTransaction().outRefsOfType(CarTokenType.class).get(0);
        final StateAndRef<NonFungibleToken> bmw1 = issueCarTo(
                bmwType.getState().getData().toPointer(CarTokenType.class),
                sellerParty)
                .getCoreTransaction().outRefsOfType(NonFungibleToken.class).get(0);

        // Seller makes an offer.
        final OfferSimpleFlow offerFlow = new OfferSimpleFlow(
                bmw1.getState().getData().getLinearId(), buyerParty, 11_000L, "USD",
                usMint.getInfo().getLegalIdentities().get(0), 3600);
        final CordaFuture<SignedTransaction> offerFuture = alice.startFlow(offerFlow);
        network.runNetwork();
        offerFuture.get().getTx().outRef(0);

        // Dmv changes the car with informing only the seller.
        final SignedTransaction mileageTx = updateMileageOn(bmwType, 8_000L, 22_000L,
                Collections.singletonList(alice.getInfo().getLegalIdentities().get(0)));
        final StateAndRef<CarTokenType> newBmwType = mileageTx.getCoreTransaction().outRef(0);

        // Only alice tracks the updated car.
        Arrays.asList(dmv, bmwDealer, bob).forEach(node -> {
            final SalesProposalService proposalService = node.getServices().cordaService(SalesProposalService.class);
            assertEquals(0, proposalService.getCarTypeCount());
        });
        final SalesProposalService aliceService = alice.getServices().cordaService(SalesProposalService.class);
        assertEquals(1, aliceService.getCarTypeCount());
        assertNull(aliceService.getBuyersOf(bmwType));
        final List<AbstractParty> buyers = aliceService.getBuyersOf(newBmwType);
        assertNotNull(buyers);
        assertEquals(1, buyers.size());
        assertEquals(buyerParty, buyers.get(0));

        // Bob got the updated car type with new mileage.
        final List<StateAndRef<CarTokenType>> updatedBmwTypes = bob.getServices().getVaultService()
                .queryBy(CarTokenType.class, new QueryCriteria.LinearStateQueryCriteria()
                        .withUuid(Collections.singletonList(bmwType.getState().getData().getLinearId().getId())))
                .getStates();
        assertEquals(1, updatedBmwTypes.size());
        assertEquals(8_000L, updatedBmwTypes.get(0).getState().getData().getMileage());
    }

    @Test
    public void whenBuyerRejectsTrackerRemoves() throws Exception {
        // Seller is on alice.
        final StateAndRef<AccountInfo> seller = createAccount(alice, "carly");
        final AnonymousParty sellerParty = requestNewKey(alice, seller.getState().getData());
        informKeys(alice, Collections.singletonList(sellerParty.getOwningKey()), Collections.singletonList(bmwDealer));
        // Buyer is on bob.
        final StateAndRef<AccountInfo> buyer = createAccount(bob, "dan");
        final AnonymousParty buyerParty = requestNewKey(bob, buyer.getState().getData());
        informKeys(bob, Collections.singletonList(buyerParty.getOwningKey()), Collections.singletonList(alice));
        // The car.
        final StateAndRef<CarTokenType> bmwType = createNewBmw("abc124", "BMW",
                Arrays.asList(
                        bmwDealer.getInfo().getLegalIdentities().get(0),
                        alice.getInfo().getLegalIdentities().get(0),
                        bob.getInfo().getLegalIdentities().get(0)))
                .getCoreTransaction().outRefsOfType(CarTokenType.class).get(0);
        final StateAndRef<NonFungibleToken> bmw1 = issueCarTo(
                bmwType.getState().getData().toPointer(CarTokenType.class),
                sellerParty)
                .getCoreTransaction().outRefsOfType(NonFungibleToken.class).get(0);

        // Seller makes an offer.
        final OfferSimpleFlow offerFlow = new OfferSimpleFlow(
                bmw1.getState().getData().getLinearId(), buyerParty, 11_000L, "USD",
                usMint.getInfo().getLegalIdentities().get(0), 3600);
        final CordaFuture<SignedTransaction> offerFuture = alice.startFlow(offerFlow);
        network.runNetwork();
        final StateAndRef<SalesProposal> proposal = offerFuture.get().getTx().outRef(0);

        // Buyer rejects.
        final RejectSimpleFlow rejectFlow = new RejectSimpleFlow(
                proposal.getState().getData().getLinearId(), buyerParty);
        final CordaFuture<SignedTransaction> rejectFuture = bob.startFlow(rejectFlow);
        network.runNetwork();
        rejectFuture.get();

        // No one is tracking.
        Arrays.asList(dmv, bmwDealer, alice, bob).forEach(node -> {
            final SalesProposalService proposalService = node.getServices().cordaService(SalesProposalService.class);
            assertEquals(0, proposalService.getCarTypeCount());
        });
    }

    @Test
    public void whenSellerRejectsTrackerRemoves() throws Exception {
        // Seller is on alice.
        final StateAndRef<AccountInfo> seller = createAccount(alice, "carly");
        final AnonymousParty sellerParty = requestNewKey(alice, seller.getState().getData());
        informKeys(alice, Collections.singletonList(sellerParty.getOwningKey()), Collections.singletonList(bmwDealer));
        // Buyer is on bob.
        final StateAndRef<AccountInfo> buyer = createAccount(bob, "dan");
        final AnonymousParty buyerParty = requestNewKey(bob, buyer.getState().getData());
        informKeys(bob, Collections.singletonList(buyerParty.getOwningKey()), Collections.singletonList(alice));
        // The car.
        final StateAndRef<CarTokenType> bmwType = createNewBmw("abc124", "BMW",
                Arrays.asList(
                        bmwDealer.getInfo().getLegalIdentities().get(0),
                        alice.getInfo().getLegalIdentities().get(0),
                        bob.getInfo().getLegalIdentities().get(0)))
                .getCoreTransaction().outRefsOfType(CarTokenType.class).get(0);
        final StateAndRef<NonFungibleToken> bmw1 = issueCarTo(
                bmwType.getState().getData().toPointer(CarTokenType.class),
                sellerParty)
                .getCoreTransaction().outRefsOfType(NonFungibleToken.class).get(0);

        // Seller makes an offer.
        final OfferSimpleFlow offerFlow = new OfferSimpleFlow(
                bmw1.getState().getData().getLinearId(), buyerParty, 11_000L, "USD",
                usMint.getInfo().getLegalIdentities().get(0), 3600);
        final CordaFuture<SignedTransaction> offerFuture = alice.startFlow(offerFlow);
        network.runNetwork();
        final StateAndRef<SalesProposal> proposal = offerFuture.get().getTx().outRef(0);

        ((TestClock) notary.getServices().getClock()).advanceBy(Duration.ofSeconds(3610));

        // Seller rejects.
        final RejectSimpleFlow rejectFlow = new RejectSimpleFlow(
                proposal.getState().getData().getLinearId(), sellerParty);
        final CordaFuture<SignedTransaction> rejectFuture = alice.startFlow(rejectFlow);
        network.runNetwork();
        rejectFuture.get();

        // No one is tracking.
        Arrays.asList(dmv, bmwDealer, alice, bob).forEach(node -> {
            final SalesProposalService proposalService = node.getServices().cordaService(SalesProposalService.class);
            assertEquals(0, proposalService.getCarTypeCount());
        });
    }

    @Test
    public void whenAcceptedTrackerRemoves() throws Exception {
        // Seller is on alice.
        final StateAndRef<AccountInfo> seller = createAccount(alice, "carly");
        final AnonymousParty sellerParty = requestNewKey(alice, seller.getState().getData());
        informKeys(alice, Collections.singletonList(sellerParty.getOwningKey()), Collections.singletonList(bmwDealer));
        // Buyer is on bob.
        final StateAndRef<AccountInfo> buyer = createAccount(bob, "dan");
        final AnonymousParty buyerParty = requestNewKey(bob, buyer.getState().getData());
        informKeys(bob, Collections.singletonList(buyerParty.getOwningKey()), Collections.singletonList(alice));
        // The car.
        final StateAndRef<CarTokenType> bmwType = createNewBmw("abc124", "BMW",
                Arrays.asList(
                        bmwDealer.getInfo().getLegalIdentities().get(0),
                        alice.getInfo().getLegalIdentities().get(0),
                        bob.getInfo().getLegalIdentities().get(0)))
                .getCoreTransaction().outRefsOfType(CarTokenType.class).get(0);
        final StateAndRef<NonFungibleToken> bmw1 = issueCarTo(
                bmwType.getState().getData().toPointer(CarTokenType.class),
                sellerParty)
                .getCoreTransaction().outRefsOfType(NonFungibleToken.class).get(0);

        // Seller makes an offer.
        final OfferSimpleFlow offerFlow = new OfferSimpleFlow(
                bmw1.getState().getData().getLinearId(), buyerParty, 11_000L, "USD",
                usMint.getInfo().getLegalIdentities().get(0), 3600);
        final CordaFuture<SignedTransaction> offerFuture = alice.startFlow(offerFlow);
        network.runNetwork();
        final StateAndRef<SalesProposal> proposal = offerFuture.get().getTx().outRef(0);

        // Issue dollars to Buyer.
        final Amount<IssuedTokenType> amountOfUsd = AmountUtilitiesKt.amount(20_000L, usMintUsd);
        final FungibleToken usdTokenBob = new FungibleToken(amountOfUsd,
                bob.getInfo().getLegalIdentities().get(0), null);
        final IssueTokens issueFlow = new IssueTokens(
                Collections.singletonList(usdTokenBob),
                Collections.emptyList());
        final CordaFuture<SignedTransaction> issueFuture = usMint.startFlow(issueFlow);
        network.runNetwork();
        issueFuture.get();

        // Buyer accepts.
        final AcceptSimpleFlow acceptFlow = new AcceptSimpleFlow(proposal.getState().getData().getLinearId());
        final CordaFuture<SignedTransaction> acceptFuture = bob.startFlow(acceptFlow);
        network.runNetwork();
        acceptFuture.get();

        // No one is tracking.
        Arrays.asList(dmv, bmwDealer, alice, bob).forEach(node -> {
            final SalesProposalService proposalService = node.getServices().cordaService(SalesProposalService.class);
            assertEquals(0, proposalService.getCarTypeCount());
        });
    }

    @Test
    public void whenChangedAndRejectedTrackerRemoves() throws Exception {
        // Seller is on alice.
        final StateAndRef<AccountInfo> seller = createAccount(alice, "carly");
        final AnonymousParty sellerParty = requestNewKey(alice, seller.getState().getData());
        informKeys(alice, Collections.singletonList(sellerParty.getOwningKey()), Collections.singletonList(bmwDealer));
        // Buyer is on bob.
        final StateAndRef<AccountInfo> buyer = createAccount(bob, "dan");
        final AnonymousParty buyerParty = requestNewKey(bob, buyer.getState().getData());
        informKeys(bob, Collections.singletonList(buyerParty.getOwningKey()), Collections.singletonList(alice));
        // The car.
        final StateAndRef<CarTokenType> bmwType = createNewBmw("abc124", "BMW",
                Arrays.asList(
                        bmwDealer.getInfo().getLegalIdentities().get(0),
                        alice.getInfo().getLegalIdentities().get(0),
                        bob.getInfo().getLegalIdentities().get(0)))
                .getCoreTransaction().outRefsOfType(CarTokenType.class).get(0);
        final StateAndRef<NonFungibleToken> bmw1 = issueCarTo(
                bmwType.getState().getData().toPointer(CarTokenType.class),
                sellerParty)
                .getCoreTransaction().outRefsOfType(NonFungibleToken.class).get(0);

        // Seller makes an offer.
        final OfferSimpleFlow offerFlow = new OfferSimpleFlow(
                bmw1.getState().getData().getLinearId(), buyerParty, 11_000L, "USD",
                usMint.getInfo().getLegalIdentities().get(0), 3600);
        final CordaFuture<SignedTransaction> offerFuture = alice.startFlow(offerFlow);
        network.runNetwork();
        final StateAndRef<SalesProposal> proposal = offerFuture.get().getTx().outRef(0);

        // Dmv changes the car with informing only the seller.
        updateMileageOn(bmwType, 8_000L, 22_000L,
                Collections.singletonList(alice.getInfo().getLegalIdentities().get(0)));

        // Buyer rejects.
        final RejectSimpleFlow rejectFlow = new RejectSimpleFlow(
                proposal.getState().getData().getLinearId(), buyerParty);
        final CordaFuture<SignedTransaction> rejectFuture = bob.startFlow(rejectFlow);
        network.runNetwork();
        rejectFuture.get();

        assertTrue(alice.getServices().getVaultService().queryBy(SalesProposal.class).getStates().isEmpty());

        // No one is tracking.
        Arrays.asList(dmv, bmwDealer, alice, bob).forEach(node -> {
            final SalesProposalService proposalService = node.getServices().cordaService(SalesProposalService.class);
            assertEquals(0, proposalService.getCarTypeCount());
        });
    }

    @Test
    public void whenChangedAndAcceptedTrackerRemoves() throws Exception {
        // Seller is on alice.
        final StateAndRef<AccountInfo> seller = createAccount(alice, "carly");
        final AnonymousParty sellerParty = requestNewKey(alice, seller.getState().getData());
        informKeys(alice, Collections.singletonList(sellerParty.getOwningKey()), Collections.singletonList(bmwDealer));
        // Buyer is on bob.
        final StateAndRef<AccountInfo> buyer = createAccount(bob, "dan");
        final AnonymousParty buyerParty = requestNewKey(bob, buyer.getState().getData());
        informKeys(bob, Collections.singletonList(buyerParty.getOwningKey()), Collections.singletonList(alice));
        // The car.
        final StateAndRef<CarTokenType> bmwType = createNewBmw("abc124", "BMW",
                Arrays.asList(
                        bmwDealer.getInfo().getLegalIdentities().get(0),
                        alice.getInfo().getLegalIdentities().get(0),
                        bob.getInfo().getLegalIdentities().get(0)))
                .getCoreTransaction().outRefsOfType(CarTokenType.class).get(0);
        final StateAndRef<NonFungibleToken> bmw1 = issueCarTo(
                bmwType.getState().getData().toPointer(CarTokenType.class),
                sellerParty)
                .getCoreTransaction().outRefsOfType(NonFungibleToken.class).get(0);

        // Seller makes an offer.
        final OfferSimpleFlow offerFlow = new OfferSimpleFlow(
                bmw1.getState().getData().getLinearId(), buyerParty, 11_000L, "USD",
                usMint.getInfo().getLegalIdentities().get(0), 3600);
        final CordaFuture<SignedTransaction> offerFuture = alice.startFlow(offerFlow);
        network.runNetwork();
        final StateAndRef<SalesProposal> proposal = offerFuture.get().getTx().outRef(0);

        // Dmv changes the car with informing only the seller.
        final SignedTransaction mileageTx = updateMileageOn(bmwType, 8_000L, 22_000L,
                Collections.singletonList(alice.getInfo().getLegalIdentities().get(0)));
        mileageTx.getCoreTransaction();

        // Issue dollars to Buyer.
        final Amount<IssuedTokenType> amountOfUsd = AmountUtilitiesKt.amount(20_000L, usMintUsd);
        final FungibleToken usdTokenBob = new FungibleToken(amountOfUsd,
                bob.getInfo().getLegalIdentities().get(0), null);
        final IssueTokens issueFlow = new IssueTokens(
                Collections.singletonList(usdTokenBob),
                Collections.emptyList());
        final CordaFuture<SignedTransaction> issueFuture = usMint.startFlow(issueFlow);
        network.runNetwork();
        issueFuture.get();

        // Buyer accepts.
        final AcceptSimpleFlow acceptFlow = new AcceptSimpleFlow(proposal.getState().getData().getLinearId());
        final CordaFuture<SignedTransaction> acceptFuture = bob.startFlow(acceptFlow);
        network.runNetwork();
        acceptFuture.get();

        // No one is tracking.
        Arrays.asList(dmv, bmwDealer, alice, bob).forEach(node -> {
            final SalesProposalService proposalService = node.getServices().cordaService(SalesProposalService.class);
            assertEquals(0, proposalService.getCarTypeCount());
        });
    }
}
