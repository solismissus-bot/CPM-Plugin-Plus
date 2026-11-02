# CPM Plugin+

Fork do plugin de Blockbench do **[Customizable Player Models](https://github.com/tom5454/CustomPlayerModels)** (CPM), de tom5454, com correções e ferramentas novas pra quem modela e anima pro CPM.

*English below.*

## Instalar

1. No Blockbench: **Arquivo > Plugins**. Desinstale o plugin oficial do CPM (os dois juntos conflitam).
2. **Carregar plugin por URL** e cole:
   `https://gist.githubusercontent.com/solismissus-bot/26923a343013328804c2bc12f7c9de6c/raw/cpm_plugin_plus.js`
3. Reinicie o Blockbench. As ferramentas ficam no menu **CPM+**.

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

**Animação:** cópia espelhada, animação rápida (inverter, velocidade), exagerar/suavizar, fechar o loop, atrasar/adiantar, suavizar com curvas, fazer onda em correntes (rabo, cabelo), movimentos prontos (respirar, flutuar, bater asas...), piscada, seletor de cor, movimento que some ao trocar de situação, otimizar animação (menos quadros), importar animações de outro .cpmproject ou de Bedrock/GeckoLib, partículas falsas com a sua imagem.

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

**CPM Plugin+** is a fork of the [Customizable Player Models](https://github.com/tom5454/CustomPlayerModels) Blockbench plugin by tom5454. It fixes animations that came out different in game, makes heavy models much faster, gives every tool a working undo, adds export warnings for things the game silently breaks (format limits, oversized animations, ghost body parts, loose cubes) and adds many modeling, animation and texture tools. The interface is in Brazilian Portuguese and English.

Install: Blockbench > File > Plugins > Load plugin from URL, then paste the link above (uninstall the official CPM plugin first). Build: JDK 17, `gradlew compileBlockbenchRelease` in `Web`. MIT License; CPM is by tom5454.
