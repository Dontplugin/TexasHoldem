package texas.server;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Random;

import javax.swing.JFrame;

import com.google.gson.Gson;

import texas.commons.CardBean;
import texas.commons.CardUtil;
import texas.commons.PlayerBean;
import texas.commons.TexasBean;

public class Dealer {
	private static final int MAX_NUM = 2;
	private ArrayList<CardBean> pile; // 牌堆
	private Gson gson;
	private TexasBean texasBean;
	private TexasBean curBean;
	private int[] position;
	private TaskManager taskManager;

	private int bankPos; // 庄位
	private int oriMoney; // 初始金钱
	private int oriBlind; // 初始大盲注
	private ArrayList<Integer> pools; // 底池，边池
	private int nextPlayerPos;
	private int smallBlindPos;
	private PlayerBean playerBean;
	private int bigBlindPos;

	public Dealer(TaskManager taskManager) {
		this.taskManager = taskManager;
		gson = new Gson();
		texasBean = new TexasBean();
		curBean = new TexasBean();
		curBean.setMaxPlayerNum(MAX_NUM);
		pile = new ArrayList<CardBean>();
		pools = new ArrayList<Integer>();
		position = new int[MAX_NUM];
		for (int i = 0; i < MAX_NUM; i++) {
			curBean.getOthers().add(new PlayerBean());
			position[i] = 0;
		}
		resetPile();

		// 初始化游戏数据
		bankPos = 0;
		oriMoney = 5000;
		oriBlind = 100;
	}

	private void resetPile() {
		pile.clear();
		for (int i = 0; i < 4; i++) {
			for (int j = 2; j < 15; j++) {
				pile.add(new CardBean(j, i));
			}
		}
	}

	// 发牌
	private ArrayList<CardBean> dealCard(int num) {
		ArrayList<CardBean> cards = new ArrayList<CardBean>();
		for (int i = 0; i < num; i++) {
			int index = new Random().nextInt(pile.size());
			cards.add(pile.get(index));
			pile.remove(index);
		}
		return cards;
	}

	public void parse(String json, String address, Task task) {
		texasBean = gson.fromJson(json, TexasBean.class);

		// 测试时用
		task.setName(texasBean.getPlayer().getName());

		int emptyPos = getEmptyPos();
		switch (texasBean.getPlayer().getState()) {
		case "connect":
			texasBean.getPlayer().setAddress(address);
			if (emptyPos == -1) {
				texasBean.getPlayer().setPos(-1);
				texasBean.getPlayer().setState("wait");
				curBean.getOthers().add(texasBean.getPlayer());
				send();
				break;
			}
			texasBean.getPlayer().setPos(emptyPos);
			texasBean.getPlayer().setMoney(oriMoney);
			updateCurBean();
			send();
			position[emptyPos] = 1;
			break;
		case "ready":
			updateCurBean();
			if (allReady()) {
				allStart();
			}
			break;
		case "call":
			updateCurBean();
			nextPlayerPos = findNextPlayer(texasBean.getPlayer().getPos());
			playerBean = curBean.getOthers().get(nextPlayerPos);
			if (playerBean.getState().equals("raise")
					&& playerBean.getBet() == curBean.getMaxBet()) {
				nextTableState();
			} else {
				curBean.getOthers().get(nextPlayerPos).setState("choose");
			}
			send();
			break;
		case "check":
			updateCurBean();
			smallBlindPos = findNextPlayer(bankPos);
			bigBlindPos = findNextPlayer(smallBlindPos);
			if (curBean.getTableState().equals("hands")
					&& texasBean.getPlayer().getPos() == bigBlindPos
					|| !curBean.getTableState().equals("hands")
					&& findNextPlayer(texasBean.getPlayer().getPos()) == smallBlindPos) {
				nextTableState();
			} else {
				nextPlayerPos = findNextPlayer(texasBean.getPlayer().getPos());
				curBean.getOthers().get(nextPlayerPos).setState("choose");
			}
			send();
			break;
		case "raise":
			updateCurBean();
			nextPlayerPos = findNextPlayer(texasBean.getPlayer().getPos());
			curBean.getOthers().get(nextPlayerPos).setState("choose");
			send();
			break;
		case "fold":
			updateCurBean();
			nextPlayerPos = findNextPlayer(texasBean.getPlayer().getPos());
			playerBean = curBean.getOthers().get(nextPlayerPos);
			if (findNextPlayer(nextPlayerPos) == -1) {
				win(nextPlayerPos);
			} else if (playerBean.getState().equals("raise")
					&& playerBean.getBet() == curBean.getMaxBet()) {
				nextTableState();
			} else {
				playerBean.setState("choose");
			}
			send();
			break;
		default:
			break;
		}
	}

	private void win(int pos) {
		for (PlayerBean playerBean : curBean.getOthers()) {
			if (playerBean.getPos() == pos) {
				playerBean.setMoney(playerBean.getMoney()
						+ curBean.getPools().get(0));
				playerBean.setState("win");
			} else {
				playerBean.setState("lose");
			}
			playerBean.setBet(0);
		}
	}

	private void nextTableState() {
		switch (curBean.getTableState()) {
		case "hands":
			curBean.setTableState("flops");
			curBean.setFlops(dealCard(3));
			curBean.getOthers().get(smallBlindPos).setState("choose");
			break;
		case "flops":
			curBean.setTableState("turn");
			curBean.setTurn(dealCard(1).get(0));
			curBean.getOthers().get(smallBlindPos).setState("choose");
			break;
		case "turn":
			curBean.setTableState("river");
			curBean.setRiver(dealCard(1).get(0));
			curBean.getOthers().get(smallBlindPos).setState("choose");
			break;
		case "river":
			curBean.setTableState("show");
			int winnerPos = findWinner();
			win(winnerPos);
			break;
		default:
			break;
		}
	}

	private int findWinner() {
		int maxType = 0;
		PlayerBean winnerBean = null;
		for (PlayerBean playerBean : curBean.getOthers()) {
			int cardType = CardUtil.recognise(playerBean,curBean);
			if (cardType > maxType) {
				winnerBean = playerBean;
				maxType = cardType;
			} else if (cardType == maxType) {
				winnerBean = CardUtil.ifWinInSameType(playerBean, winnerBean, curBean,
						maxType) ? playerBean : winnerBean;
			}
		}
		curBean.setWinnerType(maxType);
		return winnerBean.getPos();
	}

	private void updateCurBean() {
		curBean.getOthers().set(texasBean.getPlayer().getPos(),
				texasBean.getPlayer());
		curBean.setPools(texasBean.getPools());
		curBean.setMaxBet(texasBean.getMaxBet());
	}

	private void send() {
		for (PlayerBean playerBean : curBean.getOthers()) {
			if (playerBean.getState() != null) {
				curBean.setPlayer(playerBean);
				taskManager.send(gson.toJson(curBean), curBean.getPlayer()
						.getAddress(), curBean.getPlayer().getName());
			}
		}
	}

	private void allStart() {
		pools.clear();
		pools.add(new Integer(0));
		bankPos = findNextPlayer(bankPos);
		int smallBlindPos = findNextPlayer(bankPos);
		int bigBlindPos = findNextPlayer(smallBlindPos);
		int gunPos = findNextPlayer(bigBlindPos);
		for (int i = 0; i < curBean.getOthers().size(); i++) {
			PlayerBean playerBean = curBean.getOthers().get(i);
			if (playerBean.getState() != null) {
				playerBean.setState("start");
				playerBean.setHands(dealCard(2));
				if (i == smallBlindPos) {
					playerBean.setBet(oriBlind / 2);
					playerBean.setMoney(playerBean.getMoney() - oriBlind / 2);
					pools.set(0, pools.get(0) + oriBlind / 2);
				} else if (i == bigBlindPos) {
					playerBean.setBet(oriBlind);
					playerBean.setMoney(playerBean.getMoney() - oriBlind);
					pools.set(0, pools.get(0) + oriBlind);
				}
				curBean.setPlayer(playerBean);
				curBean.setMaxBet(oriBlind);
				curBean.setBigBlind(oriBlind);
			}
		}
		curBean.setPools(pools);
		curBean.setTableState("hands");
		curBean.getFlops().clear();
		curBean.setTurn(null);
		curBean.setRiver(null);
		curBean.setWinnerType(0);
		send();
		for (int i = 0; i < curBean.getOthers().size(); i++) {
			PlayerBean playerBean = curBean.getOthers().get(i);
			if (i == gunPos) {
				playerBean.setState("choose");
				send();
				break;
			}
		}
	}

	private int findNextPlayer(int curPos) {
		for (int i = curPos + 1; i < MAX_NUM; i++) {
			String state = curBean.getOthers().get(i).getState();
			if (state != null && !state.equals("fold"))
				return i;
		}
		for (int i = 0; i < curPos; i++) {
			String state = curBean.getOthers().get(i).getState();
			if (state != null && !state.equals("fold"))
				return i;
		}
		return -1;
	}

	private boolean allReady() {
		int playerNum = 0;
		for (PlayerBean playerBean : curBean.getOthers()) {
			String state = playerBean.getState();
			if (state != null && state.equals("ready"))
				playerNum++;
		}
		if (playerNum > 1)
			return true;
		else {
			return false;
		}
	}

	private int getEmptyPos() {
		for (int i = 0; i < MAX_NUM; i++) {
			if (position[i] == 0) {
				return i;
			}
		}
		return -1;
	}
}
