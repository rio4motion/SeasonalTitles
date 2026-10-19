package dev.seasonal.titles;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;
import org.junit.jupiter.api.Test;

class VisibilityTest {
  @Test
  void reportsVanillaLabelVisibilityWithoutChangingTeams() {
    Player viewer = mock(Player.class), owner = mock(Player.class);
    Scoreboard board = mock(Scoreboard.class);
    Team team = mock(Team.class);
    when(viewer.getScoreboard()).thenReturn(board);
    when(owner.getName()).thenReturn("Owner");
    when(viewer.getName()).thenReturn("Viewer");
    when(board.getEntryTeam("Owner")).thenReturn(team);
    when(team.getOption(Team.Option.NAME_TAG_VISIBILITY)).thenReturn(Team.OptionStatus.NEVER);
    assertFalse(TitleRenderer.teamVisible(viewer, owner));
    when(team.getOption(Team.Option.NAME_TAG_VISIBILITY))
        .thenReturn(Team.OptionStatus.FOR_OWN_TEAM);
    assertFalse(TitleRenderer.teamVisible(viewer, owner));
    when(board.getEntryTeam("Viewer")).thenReturn(team);
    assertTrue(TitleRenderer.teamVisible(viewer, owner));
    when(team.getOption(Team.Option.NAME_TAG_VISIBILITY))
        .thenReturn(Team.OptionStatus.FOR_OTHER_TEAMS);
    assertFalse(TitleRenderer.teamVisible(viewer, owner));
    when(board.getEntryTeam("Viewer")).thenReturn(null);
    assertTrue(TitleRenderer.teamVisible(viewer, owner));
    verify(team, never()).setOption(any(), any());
  }
}
