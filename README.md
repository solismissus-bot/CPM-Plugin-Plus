# CPM Plugin+

Fork do plugin de Blockbench do **[Customizable Player Models](https://github.com/tom5454/CustomPlayerModels)** (CPM), de tom5454, com correções e ferramentas novas pra quem modela e anima pro CPM.

*English below.*

## Instalar

1. No Blockbench: **Arquivo > Plugins**. Desinstale o plugin oficial do CPM (os dois juntos conflitam).
2. **Carregar plugin por URL** e cole:
   `https://raw.githubusercontent.com/solismissus-bot/CPM-Plugin-Plus/main/plugin/cpm_plugin_plus.js`
3. Reinicie o Blockbench. As ferramentas ficam no menu **CPM+**.

O arquivo do plugin fica na pasta [plugin/](plugin/). Quem instalou pelo link antigo do gist não precisa fazer nada: aquele link continua funcionando e puxa a versão nova daqui.

**Como usar cada ferramenta:** [COMO_USAR.md](COMO_USAR.md).

Guia do que testar em cada versão: [COMO_TESTAR.md](https://gist.github.com/solismissus-bot/26923a343013328804c2bc12f7c9de6c).

## O que muda em relação ao plugin oficial

**Correções**
- Animação sai igual no jogo: sem quadros extras, interpolação suave mantida, toggle de 1 quadro, fórmulas (Molang) convertidas certo.
- Modelos pesados abrem, salvam e exportam bem mais rápido.
- Ctrl+Z que funciona em "Adicionar partes / slots", no conserto de raiz desconhecida e em todas as ferramentas novas.
- Avisos no export (nunca bloqueiam), com o valor que o jogo vai receber e conserto de 1 clique quando dá:
  - limites do jogo (cubo > 25,5 px, posição > 48 px);
  - animação grande demais (255 quadros, 32,7 s, 255 peças);
  - parte do corpo "fantasma" (apagou no Blockbench, aparece no jogo);
  - peças soltas fora de qualquer parte do corpo;
  - animações soltas de pastas apagadas;
  - ID do modelo faltando / botão renomeado.

**Animação:** cópia espelhada, animação rápida (inverter, velocidade), exagerar/suavizar, fechar o loop, atrasar/adiantar, suavizar com curvas, fazer onda em correntes (rabo, cabelo), movimentos prontos (respirar, flutuar, bater asas...), piscada, seletor de cor, movimento que some ao trocar de situação, otimizar animação (menos quadros), importar animações de outro .cpmproject ou de Bedrock/GeckoLib, partículas falsas com a sua imagem, copiar/colar/espelhar pose de várias partes, pose intermediária (tween), animar bloco por bloco.

**Animação profissional (beta28):** física que segue o movimento (rabo, orelha, cabelo, capa) numa camada que toca junto no jogo, ajuste relativo em todas as keys, camada de ajuste (criar, força, fundir), entrada e saída suaves entre poses + relatório de estalos, editar keys em várias animações, Raio-X (quem mexe cada parte, aditiva ou não) + sub-grupo de animação, rastro das pontas, conferir movimento, importar emote do Emotecraft/PlayerAnimator, inserir clipe, prévia em degrau, silhueta, curvas que não passam do ponto, esticar/achatar, pivô temporário, takes. Pra quem está começando: nova animação pelo que você quer, gerar andar/correr (2 ou 4 patas), kit de rosto, testar no editor do CPM e "vi isso num vídeo".

**Arte e cores (beta29, na tela de pintura do Blockbench):** pincel **Sombra** (clareia/escurece com luz quente e sombra fria, anda 1 tom na rampa, ou matiz/saturação/luz), **Esfumar** (suave, ou arrastando/misturando pixels sem criar cor nova), **Pontilhado** (Bayer, linhas, pontos), **Variação** (ruído pixel a pixel), estilo **Pixel art** no Degradê (degraus, rampas, pontilhado entre tons vizinhos), **Gerar rampa** de cores com mudança de matiz, **Trocar cores** de uma família inteira mantendo o sombreado, **Ruído na textura** e **Sombra automática** pra cubos (luz por face, degradê, sombra de contato, bordas).

**Modelagem:** espelhar pro outro lado, conferir articulações, montar corrente, ver esqueleto, membros dobráveis, curvar peça em arco, grudar na parte do corpo, editor de Copy Transform, camada de armadura, efeitos de pasta (sem animação do Minecraft, slot de item, cópia da parte do corpo).

**Textura:** pintar por baixo da 2ª camada, virar cor sólida, conferir simetria.

**Levar pro jogo:** exportar .cpmmodel, skin com o modelo dentro, testar no jogo agora, "quem vê meu modelo?", ajustes do modelo (braço Steve/Alex, nome, descrição, ícone, tamanho e atributos, ID do modelo).

## Compilar

Precisa de **JDK 17**. Na pasta `Web`:

```
gradlew compileBlockbenchRelease -PbbVersion=0.8.0_plus -PbbPluginId=cpm_plugin_plus
```

O plugin sai em `Web/build/dist/cpm_plugin_plus.js`. O código do plugin fica em `Web/src/blockbench`; o código compartilhado do CPM em `CustomPlayerModels/src/shared`.

## Créditos e licença

Licença **MIT** (veja [LICENSE](LICENSE)). Customizable Player Models e o plugin original são de **tom5454**. As modificações do CPM Plugin+ foram feitas com a ajuda do Claude (Anthropic).

---

## English

**CPM Plugin+** is a fork of the [Customizable Player Models](https://github.com/tom5454/CustomPlayerModels) Blockbench plugin by tom5454. It fixes animations that came out different in game, makes heavy models much faster, gives every tool a working undo, adds export warnings for things the game silently breaks (format limits, oversized animations, ghost body parts, loose cubes) and adds many modeling, animation and texture tools: follow-through physics baked into an additive layer, relative adjust across keys and animations, correction layers, CPM entry/exit transitions, batch key edits, an "X-ray" of which body parts each animation takes over, tip trails, a motion checker, Emotecraft/PlayerAnimator emote import, a clip sequencer, takes, recipes for beginners and more. Since beta29 it also adds pixel-art painting tools to Blockbench's Paint mode: a Shade brush (hue-shifted lighten/darken, ramp steps, HSL), Smudge (smooth or palette-safe), Dither, Variation noise, a Pixel art style for the Gradient tool (steps, ramps, ordered dither), a ramp generator, colour family swap, texture noise and auto shading for cube models (light per face, gradient, contact shadow, edges). The interface is in Brazilian Portuguese and English.

Install: Blockbench > File > Plugins > Load plugin from URL, then paste `https://raw.githubusercontent.com/solismissus-bot/CPM-Plugin-Plus/main/plugin/cpm_plugin_plus.js` (uninstall the official CPM plugin first). Build: JDK 17, `gradlew compileBlockbenchRelease` in `Web`. MIT License; CPM is by tom5454.
