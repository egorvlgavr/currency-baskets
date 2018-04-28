package com.currencybaskets.services;

import com.currencybaskets.dao.model.Account;
import com.currencybaskets.dao.model.Rate;
import com.currencybaskets.dao.repository.AccountRepository;
import com.currencybaskets.dao.repository.RateRepository;
import com.currencybaskets.dto.AccountUpdate;
import com.currencybaskets.dto.AggregatedAmountDto;
import com.currencybaskets.view.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AccountService {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private RateRepository rateRepository;

    public LatestAccountsView getUserLatestAccounts(List<Long> userIds) {
        List<Account> latestAccountByUserId = accountRepository.findLatestAccountByUserIds(userIds);
        List<AccountView> accountViews = new ArrayList<>(latestAccountByUserId.size());
        Set<RateView> rates = new HashSet<>();
        Date latestRatesUpdated = null;
        BigDecimal totalBase = new BigDecimal(0);
        for (Account account : latestAccountByUserId) {
            accountViews.add(AccountView.fromEntity(account));
            Rate rate = account.getRate();
            if (Objects.nonNull(rate)) {
                rates.add(RateView.fromEntity(rate));

                Date updated = rate.getUpdated();
                if (Objects.nonNull(updated)) {
                    if (Objects.isNull(latestRatesUpdated)) {
                        latestRatesUpdated = updated;
                    } else {
                        latestRatesUpdated = latest(updated, latestRatesUpdated);
                    }
                } else {
                    log.warn("Rate={} with null updated field", rate.getId());
                }

            }
            totalBase = totalBase.add(account.getAmountBase());
        }
        ZonedDateTime now = ZonedDateTime.now();
        Date monthAgo = Date.from(now.minusMonths(1).toInstant());
        Date weekAgo = Date.from(now.minusWeeks(1).toInstant());

        return LatestAccountsView.builder()
                .accounts(accountViews)
                .rates(rates)
                .latestRatesUpdated(latestRatesUpdated)
                .totalAmount(totalBase)
                .weekBaseAmountChange(findAmountBaseChange(totalBase, userIds, weekAgo))
                .monthBaseAmountChange(findAmountBaseChange(totalBase, userIds, monthAgo))
                .build();
    }

    private AmountChangeView findAmountBaseChange(BigDecimal currentAmount, List<Long> userIds, Date from) {
        BigDecimal previousAmount = accountRepository.sumOfBaseAmountsForUserIdsLessThan(userIds, from);
        BigDecimal change = Objects.nonNull(previousAmount)
                ? currentAmount.subtract(previousAmount)
                : BigDecimal.ZERO;
        String background = attributeBasedOnChange(change, "bg-success", "bg-danger", "bg-primary");
        String icon = attributeBasedOnChange(change, "fa-long-arrow-up", "fa-long-arrow-down", "fa-ban");
        return new AmountChangeView(change, background, icon);
    }

    private static String attributeBasedOnChange(BigDecimal value, String possitive,
                                          String negative, String zero) {
        String result;
        if (value.compareTo(BigDecimal.ZERO) > 0) {
            result = possitive;
        } else if (value.compareTo(BigDecimal.ZERO) < 0) {
            result = negative;
        } else {
            result = zero;
        }
        return result;
    }

    private static Date latest(Date left, Date right) {
        return left.after(right) ? left : right;
    }

    public List<AggregatedAmountDto> getAggregatedAmount(List<Long> userIds) {
        return accountRepository.aggregateCurrencyForLatestAccountsByUserIds(userIds)
                .stream()
                .map(AggregatedAmountDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional
    public void updateAccountAmount(AccountUpdate update) {
        Account previous = accountRepository.findOne(update.getId());
        if (previous != null) {
            Account incrementalUpdate = createAccountAmountUpdate(previous, new BigDecimal(update.getAmount()));
            accountRepository.save(incrementalUpdate);
            log.debug("Update account with id={} on amount={}", previous.getId(), update.getAmount());
        } else {
            // TODO throw exception and handle it properly
            log.error("Not found account for id={}", update.getId());
        }
    }

    private static Account createAccountAmountUpdate(Account previous, BigDecimal newAmount) {
        Account updated = new Account();
        updated.setBank(previous.getBank());
        updated.setCurrency(previous.getCurrency());
        updated.setUser(previous.getUser());
        Rate rate = previous.getRate();
        updated.setRate(rate);
        updated.setPreviousId(previous.getId());
        updated.setVersion(previous.getVersion() + 1);
        updated.setAmount(newAmount);
        updated.setAmountChange(newAmount.subtract(previous.getAmount()));
        BigDecimal newAmountBase = Objects.nonNull(rate)
                ? newAmount.multiply(rate.getRate())
                : newAmount;
        updated.setAmountBase(newAmountBase);
        updated.setAmountBaseChange(newAmountBase.subtract(previous.getAmountBase()));
        updated.setUpdated(new Date());
        return updated;
    }

    public void updateAccountsRate(RateUpdate update) {
        Rate previousRate = rateRepository.findOne(update.getId());
        if (Objects.isNull(previousRate)) {
            // TODO throw exception and handle it properly
            log.error("No rate with id={}", update.getId());
            return;
        }
        Rate rate = rateRepository.save(createRateUpdate(previousRate, update.getRate()));
        List<Account> accountsToUpdate = accountRepository.findLatestAccountByRateId(update.getId());
        if (accountsToUpdate.isEmpty()) {
            log.warn("No accounts with rate id={}", update.getId());
            return;
        }
        List<Account> updatedAccounts = accountsToUpdate.stream()
                .map(toUpdate -> {
                    log.debug("Update account with id={} on rate={}", toUpdate.getId(), update.getRate());
                    return createAccountRateUpdate(toUpdate, rate);
                })
                .collect(Collectors.toList());
        accountRepository.save(updatedAccounts);
    }

    private static Rate createRateUpdate(Rate previous, BigDecimal newRate) {
        Rate updated = new Rate();
        updated.setCurrency(previous.getCurrency());
        updated.setRate(newRate);
        updated.setUpdated(new Date());
        updated.setVersion(previous.getVersion() + 1);
        return updated;
    }

    private static Account createAccountRateUpdate(Account previous, Rate newRate) {
        Account updated = new Account();
        updated.setBank(previous.getBank());
        updated.setCurrency(previous.getCurrency());
        updated.setUser(previous.getUser());
        updated.setRate(newRate);
        updated.setPreviousId(previous.getId());
        updated.setVersion(previous.getVersion() + 1);
        BigDecimal amount = previous.getAmount();
        updated.setAmount(amount);
        updated.setAmountChange(previous.getAmountChange());
        BigDecimal newAmountBase = amount.multiply(newRate.getRate());
        updated.setAmountBase(newAmountBase);
        updated.setAmountBaseChange(newAmountBase.subtract(previous.getAmountBase()));
        updated.setUpdated(new Date());
        return updated;
    }
}
