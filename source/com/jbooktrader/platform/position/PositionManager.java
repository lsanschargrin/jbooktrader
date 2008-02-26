package com.jbooktrader.platform.position;

import com.ib.client.*;
import com.jbooktrader.platform.model.*;
import com.jbooktrader.platform.report.Report;
import com.jbooktrader.platform.strategy.Strategy;
import com.jbooktrader.platform.trader.TraderAssistant;

import java.util.*;

/**
 * Position manager keeps track of current positions and P&L.
 */
public class PositionManager {
    private final int multiplier;
    private final double commissionRate;
    private final List<Position> positionsHistory;
    private final Strategy strategy;
    private final Report eventReport;
    private final ProfitAndLossHistory profitAndLossHistory;
    private final TraderAssistant traderAssistant;

    private int position, trades, profitableTrades, unprofitableTrades;
    private double profitAndLoss, totalProfitAndLoss, avgFillPrice;
    private double grossProfit, grossLoss, profitFactor, peakTotalProfitAndLoss, maxDrawdown;
    private double totalBought, totalSold;
    private double commission, totalCommission;
    private double kellyCriterion;
    private volatile boolean orderExecutionPending;


    public PositionManager(Strategy strategy, int multiplier, double commissionRate) throws JBookTraderException {
        this.strategy = strategy;
        this.multiplier = multiplier;
        this.commissionRate = commissionRate;
        profitAndLossHistory = new ProfitAndLossHistory();
        positionsHistory = new ArrayList<Position>();
        eventReport = Dispatcher.getReporter();
        traderAssistant = Dispatcher.getTrader().getAssistant();
    }

    public List<Position> getPositionsHistory() {
        return positionsHistory;
    }

    public int getTrades() {
        return trades;
    }

    public double getProfitFactor() {
        return profitFactor;
    }

    public double getMaxDrawdown() {
        return maxDrawdown;
    }

    public int getPosition() {
        return position;
    }

    public void setAvgFillPrice(double avgFillPrice) {
        this.avgFillPrice = avgFillPrice;
    }

    public double getAvgFillPrice() {
        return avgFillPrice;
    }

    public double getProfitAndLoss() {
        return profitAndLoss;
    }

    public double getCommission() {
        return commission;
    }

    public double getTotalProfitAndLoss() {
        return totalProfitAndLoss;
    }

    public ProfitAndLossHistory getProfitAndLossHistory() {
        return profitAndLossHistory;
    }

    public double getKellyCriterion() {
        return kellyCriterion;
    }

    public synchronized void update(OpenOrder openOrder) {
        trades++;
        Order order = openOrder.getOrder();
        String action = order.m_action;
        int sharesFilled = openOrder.getSharesFilled();
        int quantity = 0;

        if (action.equals("SELL")) {
            quantity = -sharesFilled;
        }

        if (action.equals("BUY")) {
            quantity = sharesFilled;
        }

        // current position after the execution
        position += quantity;
        avgFillPrice = openOrder.getAvgFillPrice();

        double tradeAmount = avgFillPrice * Math.abs(quantity) * multiplier;
        if (quantity > 0) {
            totalBought += tradeAmount;
        } else {
            totalSold += tradeAmount;
        }

        commission = Math.abs(quantity) * commissionRate;
        totalCommission += commission;


        double positionValue = position * avgFillPrice * multiplier;
        double previousProfitandLoss = totalProfitAndLoss;
        totalProfitAndLoss = totalSold - totalBought + positionValue - totalCommission;

        profitAndLoss = totalProfitAndLoss - previousProfitandLoss;

        if (profitAndLoss >= 0) {
            profitableTrades++;
            grossProfit += profitAndLoss;
        } else {
            unprofitableTrades++;
            grossLoss += profitAndLoss;
        }

        profitFactor = Math.abs(grossProfit / grossLoss);

        if (totalProfitAndLoss > peakTotalProfitAndLoss) {
            peakTotalProfitAndLoss = totalProfitAndLoss;
        }

        double drawdown = peakTotalProfitAndLoss - totalProfitAndLoss;
        if (drawdown > maxDrawdown) {
            maxDrawdown = drawdown;
        }

        if (profitableTrades > 0 && unprofitableTrades > 0) {
            double aveProfit = grossProfit / profitableTrades;
            double aveLoss = Math.abs(grossLoss) / unprofitableTrades;
            double winLossRatio = aveProfit / aveLoss;
            double probabilityOfWin = (double) profitableTrades / trades;
            double probabilityOfLoss = 1 - probabilityOfWin;
            kellyCriterion = probabilityOfWin - (probabilityOfLoss / winLossRatio);
            kellyCriterion *= 100;
            if (kellyCriterion < 0) {
                kellyCriterion = 0;
            }
        } else {
            kellyCriterion = 0;
        }

        long time = strategy.getTime();
        profitAndLossHistory.add(new ProfitAndLoss(time, totalProfitAndLoss));
        positionsHistory.add(new Position(openOrder.getDate(), position, avgFillPrice));


        if ((Dispatcher.getMode() != Dispatcher.Mode.OPTIMIZATION)) {
            StringBuilder msg = new StringBuilder();
            msg.append(strategy.getName()).append(": ");
            msg.append("Order ").append(openOrder.getId()).append(" is filled.  ");
            msg.append("Avg Fill Price: ").append(avgFillPrice).append(". ");
            msg.append("Position: ").append(getPosition());
            eventReport.report(msg.toString());
            strategy.report();
        }

        orderExecutionPending = false;

    }

    public void trade() {
        if (!orderExecutionPending) {
            int newPosition = strategy.getPosition();
            int quantity = newPosition - position;
            if (quantity != 0) {
                orderExecutionPending = true;
                String action = (quantity > 0) ? "BUY" : "SELL";
                Contract contract = strategy.getContract();
                traderAssistant.placeMarketOrder(contract, Math.abs(quantity), action, strategy);
            }
        }
    }
}